package dev.patchbay.core.adapter.kasa

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.ManualAdd
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * TP-Link Kasa plugs and switches over the legacy local protocol (TCP/UDP 9999). No account, no cloud.
 * Plugs that only speak KLAP (newer firmware) don't answer here and are not found; they show up in the
 * network census instead. The protocol is unauthenticated: anyone on the LAN can switch these plugs
 * (THREAT_MODEL §6). We trust only the network source address and parse strictly.
 */
public class KasaAdapter(
    private val netGuard: NetGuard,
    @Suppress("unused") private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter {

    public data class Config(
        /** Where scans go. Real homes: limited broadcast. Tests: simulator addresses. */
        val scanTargets: List<String> = listOf("255.255.255.255"),
        val port: Int = KasaProtocol.PORT,
        val scanWindowMs: Long = 2_400,
        /** The discovery datagram is sent this many times across the window (UDP is lossy; plugs sometimes miss one). */
        val scanAttempts: Int = 3,
        /** Also broadcast on each connected network's own broadcast address, not only 255.255.255.255. */
        val includeLocalBroadcasts: Boolean = true,
        /** A plug that answers here but not on [port] has newer (KLAP) firmware. */
        val webPort: Int = 80,
        val ioTimeoutMs: Int = 2_000,
        val pollIntervalMs: Long = 5_000,
        val maxBackoffMs: Long = 60_000,
    )

    override val id: AdapterId = ID
    override val systemName: String = "TP-Link Kasa plugs"
    override val discoveryNote: String =
        "Finds plugs on older firmware only. A plug that never appears usually has newer firmware (it needs your TP-Link login, not supported yet). Use Add by address to check one."
    override val manualAdd: ManualAdd = ManualAdd(ID, "TP-Link Kasa plug", "Name (optional: the plug's own name is used)", keyOptional = true)

    private val known = ConcurrentHashMap<DeviceId, LanAddress>()
    private val locks = ConcurrentHashMap<LanAddress, Mutex>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()

    /** Inbound messages rejected as out of spec (diagnostics only; never holds payload contents). */
    @Volatile public var rejectedMessages: Long = 0
        private set

    override fun discover(): Flow<DiscoveredDevice> = channelFlow {
        val udp = (netGuard.openUdp(0) as? PatchbayResult.Ok)?.value ?: return@channelFlow
        try {
            val targets = (config.scanTargets + if (config.includeLocalBroadcasts) netGuard.localBroadcastAddresses() else emptyList())
                .distinct().mapNotNull { (netGuard.address(it) as? PatchbayResult.Ok)?.value }
            val seen = HashSet<String>()
            val attempts = config.scanAttempts.coerceAtLeast(1)
            val slice = config.scanWindowMs / attempts
            repeat(attempts) {
                for (t in targets) udp.send(t, config.port, KasaProtocol.discoveryDatagram)
                val sliceEnd = clock() + slice
                while (clock() < sliceEnd && currentCoroutineContext().isActive) {
                    val d = (udp.receive(KasaProtocol.MAX_MESSAGE_BYTES, 100) as? PatchbayResult.Ok)?.value ?: continue
                    when (val r = KasaProtocol.parseSysinfo(KasaProtocol.unscramble(d.payload))) {
                        is PatchbayResult.Err -> if (r.error !is PatchbayError.Unsupported) rejectedMessages++
                        is PatchbayResult.Ok -> if (seen.add(r.value.deviceKey)) {
                            known[deviceIdFor(r.value.deviceKey)] = d.from
                            send(discovered(r.value, d.from))
                        }
                    }
                }
            }
        } finally {
            udp.close()
        }
    }

    private fun discovered(info: KasaProtocol.Sysinfo, from: LanAddress) = DiscoveredDevice(
        adapter = ID, key = info.deviceKey, address = from.literal, port = config.port,
        kind = DeviceKind.PLUG, vendor = "TP-Link", model = info.model,
        displayName = info.alias.ifBlank { null },
        controlPath = ControlPath.LOCAL, seenAt = EpochMillis(clock()),
    )

    /** For Add by address: a placeholder entry; [resolveManual] then asks the plug who it is. */
    override fun manualDevice(address: String, key: String, choice: String?): PatchbayResult<DiscoveredDevice> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        return PatchbayResult.Ok(
            DiscoveredDevice(
                adapter = ID, key = "pending", address = a.literal, port = config.port, kind = DeviceKind.PLUG,
                vendor = "TP-Link", model = null, displayName = null, controlPath = ControlPath.LOCAL, seenAt = EpochMillis(clock()),
            ),
        )
    }

    /**
     * Asks the plug at the address for its identity over the legacy protocol. If it doesn't answer there, checks the web
     * port to tell "newer firmware" apart from "wrong address or offline", so the message says which.
     */
    override suspend fun resolveManual(found: DiscoveredDevice): PatchbayResult<DiscoveredDevice> {
        val addr = when (val r = netGuard.address(found.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val reply = when (val r = exchange(addr, KasaProtocol.GET_SYSINFO)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return PatchbayResult.Err(unansweredOnLegacyPort(addr, r.error))
        }
        val info = when (val r = KasaProtocol.parseSysinfo(reply)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { rejectedMessages++; return r }
        }
        known[deviceIdFor(info.deviceKey)] = addr
        return PatchbayResult.Ok(discovered(info, addr))
    }

    private suspend fun unansweredOnLegacyPort(addr: LanAddress, cause: PatchbayError): PatchbayError {
        val web = netGuard.connectTcp(addr, config.webPort, 1_500)
        return if (web is PatchbayResult.Ok) {
            web.value.close()
            PatchbayError.Unsupported(
                "That plug answers, but not on Kasa's older local protocol (port ${config.port}). It has newer firmware that needs your TP-Link login, which Patchbay doesn't support yet.",
            )
        } else {
            PatchbayError.Unreachable("No answer from that address (${cause.message}). Check the IP and that the plug is on this network.")
        }
    }

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> =
        when (val a = netGuard.address(address)) {
            is PatchbayResult.Ok -> { known[device] = a.value; PatchbayResult.Ok(Unit) }
            is PatchbayResult.Err -> a
        }

    /** No pairing: we confirm the plug answers and store no secret. */
    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val id = deviceIdFor(device.key)
        return when (val r = status(id)) {
            is PatchbayResult.Ok -> PatchbayResult.Ok(Pairing(id, ByteArray(0), pinnedFingerprint = null))
            is PatchbayResult.Err -> r
        }
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = deviceIdFor(found.key)

    override fun capabilities(device: DeviceId): List<Capability> = CAPABILITIES

    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = config.pollIntervalMs
        while (currentCoroutineContext().isActive) {
            when (val r = status(device)) {
                is PatchbayResult.Ok -> {
                    failures = 0
                    backoff = config.pollIntervalMs
                    emit(DeviceSnapshot(device, Reachability.ONLINE, listOf(CapabilityState.PowerState(r.value.relayOn)), EpochMillis(clock())))
                }
                is PatchbayResult.Err -> {
                    failures++
                    emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                    backoff = min(backoff * 2, config.maxBackoffMs)
                }
            }
            delay(if (failures == 0) config.pollIntervalMs else backoff)
        }
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        if (command !is Command.SetPower) return PatchbayResult.Err(PatchbayError.Unsupported("Kasa plugs don't support ${command::class.simpleName}"))
        val addr = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown Kasa device"))
        return when (val r = exchange(addr, KasaProtocol.setRelay(command.on))) {
            is PatchbayResult.Ok -> KasaProtocol.parseSetRelay(r.value).also { if (it is PatchbayResult.Err) rejectedMessages++ }
            is PatchbayResult.Err -> r
        }
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        if (known[device] == null) return AdapterHealth(HealthState.UNPAIRED, null, "not discovered yet")
        val ok = status(device) is PatchbayResult.Ok
        return AdapterHealth(if (ok) HealthState.OK else HealthState.UNREACHABLE, lastContact[device]?.let(::EpochMillis), null)
    }

    public suspend fun status(device: DeviceId): PatchbayResult<KasaProtocol.Sysinfo> {
        val addr = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown Kasa device"))
        return when (val r = exchange(addr, KasaProtocol.GET_SYSINFO)) {
            is PatchbayResult.Err -> r
            is PatchbayResult.Ok -> KasaProtocol.parseSysinfo(r.value).also {
                if (it is PatchbayResult.Ok) lastContact[device] = clock() else rejectedMessages++
            }
        }
    }

    /** One request/response over a fresh TCP connection (plugs allow few concurrent ones), serialised per plug. */
    private suspend fun exchange(addr: LanAddress, message: String): PatchbayResult<ByteArray> =
        locks.getOrPut(addr) { Mutex() }.withLock {
            val socket = when (val c = netGuard.connectTcp(addr, config.port, config.ioTimeoutMs)) {
                is PatchbayResult.Ok -> c.value
                is PatchbayResult.Err -> return@withLock c
            }
            withContext(Dispatchers.IO) {
                try {
                    socket.use {
                        it.getOutputStream().apply { write(KasaProtocol.frame(message)); flush() }
                        val input = it.getInputStream()
                        val header = input.readExactly(4)
                        when (val len = KasaProtocol.frameLength(header)) {
                            is PatchbayResult.Err -> len
                            is PatchbayResult.Ok -> PatchbayResult.Ok(KasaProtocol.unscramble(input.readExactly(len.value)))
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    PatchbayResult.Err(PatchbayError.Timeout("Kasa plug didn't answer"))
                } catch (e: IOException) {
                    PatchbayResult.Err(PatchbayError.Unreachable("Kasa connection failed: ${e.javaClass.simpleName}"))
                }
            }
        }

    private fun java.io.InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r < 0) throw IOException("closed")
            off += r
        }
        return out
    }

    public companion object {
        public val ID: AdapterId = AdapterId("kasa-legacy")

        public fun deviceIdFor(key: String): DeviceId = DeviceId("kasa-$key")

        public val CAPABILITIES: List<Capability> = listOf(Capability.Power)
    }
}
