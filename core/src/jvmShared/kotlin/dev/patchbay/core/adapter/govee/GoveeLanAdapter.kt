package dev.patchbay.core.adapter.govee

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.GuardedUdp
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Govee LAN adapter (UDP). Requires "LAN Control" to be enabled per device in the Govee app; devices
 * that don't support it never answer the scan, so discovery only ever finds LAN-capable devices.
 *
 * Security note: the protocol is unauthenticated cleartext. Anyone on the LAN can control these lights
 * or forge status (THREAT_MODEL §6). We mitigate forgery by trusting only the UDP source address and by
 * strict parsing; we can't fix the protocol.
 */
public class GoveeLanAdapter(
    private val netGuard: NetGuard,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter, AutoCloseable {

    public data class Config(
        /** Where scans go. Real homes: the multicast group. Tests: simulator addresses. */
        val scanTargets: List<String> = listOf(GoveeProtocol.SCAN_GROUP),
        val scanPort: Int = GoveeProtocol.SCAN_PORT,
        /** Devices always reply to this port, regardless of our source port. 0 = ephemeral (tests only). */
        val listenPort: Int = GoveeProtocol.LISTEN_PORT,
        val commandPort: Int = GoveeProtocol.COMMAND_PORT,
        val scanWindowMs: Long = 2_000,
        val statusTimeoutMs: Long = 1_500,
        val pollIntervalMs: Long = 5_000,
        /** Minimum gap between datagrams to one device; Govee firmware drops rapid bursts. */
        val minCommandGapMs: Long = 120,
        val maxBackoffMs: Long = 60_000,
        val bindLoopbackOnly: Boolean = false,
    )

    override val id: AdapterId = ID
    override val systemName: String = "Govee lights"
    override val discoveryNote: String =
        "Turn on “LAN Control” for each light in the Govee app (not every model has it). Lights reply on UDP port 4002, so if Windows Firewall blocked Patchbay, allow it on Private networks."

    private val known = ConcurrentHashMap<DeviceId, LanAddress>()
    private val pendingStatus = ConcurrentHashMap<LanAddress, CompletableDeferred<GoveeProtocol.Message.Status>>()
    private val scanReplies = MutableSharedFlow<Pair<LanAddress, GoveeProtocol.Message.ScanReply>>(extraBufferCapacity = 64)
    private val lastSend = ConcurrentHashMap<LanAddress, Long>()
    private val sendLock = Mutex()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()

    private var socket: GuardedUdp? = null
    private var receiver: Job? = null

    /** Rejected inbound messages (for diagnostics). Never contains payload contents. */
    @Volatile public var rejectedMessages: Long = 0
        private set

    /** Binds the listen socket and starts the receive loop. Idempotent. */
    public fun start(): PatchbayResult<Int> {
        socket?.let { return PatchbayResult.Ok(it.localPort) }
        val s = when (val r = netGuard.openUdp(config.listenPort, config.bindLoopbackOnly)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return PatchbayResult.Err(
                PatchbayError.Unreachable("Govee needs UDP port ${config.listenPort}; is another app (e.g. Home Assistant) using it?"),
            )
        }
        socket = s
        receiver = scope.launch {
            while (isActive && !s.isClosed) {
                val d = (s.receive(GoveeProtocol.MAX_MESSAGE_BYTES, 1_000) as? PatchbayResult.Ok)?.value ?: continue
                when (val m = GoveeProtocol.parse(d.payload)) {
                    is PatchbayResult.Err -> rejectedMessages++
                    is PatchbayResult.Ok -> when (val msg = m.value) {
                        is GoveeProtocol.Message.ScanReply -> scanReplies.tryEmit(d.from to msg)
                        is GoveeProtocol.Message.Status -> pendingStatus.remove(d.from)?.complete(msg)
                    }
                }
            }
        }
        return PatchbayResult.Ok(s.localPort)
    }

    override fun discover(): Flow<DiscoveredDevice> = channelFlow {
        val s = socket ?: run { start(); socket } ?: return@channelFlow
        val seen = HashSet<String>()
        // Subscribe before sending the scan so no early reply is missed.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            scanReplies.collect { (from, reply) ->
                if (seen.add(reply.deviceKey)) {
                    val deviceId = deviceIdFor(reply.deviceKey)
                    known[deviceId] = from
                    send(
                        DiscoveredDevice(
                            adapter = ID, key = reply.deviceKey, address = from.literal, port = config.commandPort,
                            kind = DeviceKind.LIGHT, vendor = "Govee", model = reply.sku, displayName = null,
                            controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
                        ),
                    )
                }
            }
        }
        for (t in config.scanTargets) {
            val target = (netGuard.address(t) as? PatchbayResult.Ok)?.value ?: continue
            paced(target) { s.send(target, config.scanPort, GoveeProtocol.scan) }
        }
        delay(config.scanWindowMs)
        collector.cancel()
    }

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> =
        when (val a = netGuard.address(address)) {
            is PatchbayResult.Ok -> { known[device] = a.value; PatchbayResult.Ok(Unit) }
            is PatchbayResult.Err -> a
        }

    /** Govee LAN has no pairing. We confirm the device answers a status query, and store no secret. */
    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val deviceId = deviceIdFor(device.key)
        return when (val r = status(deviceId)) {
            is PatchbayResult.Ok -> PatchbayResult.Ok(Pairing(deviceId, ByteArray(0), pinnedFingerprint = null))
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
                    emit(DeviceSnapshot(device, Reachability.ONLINE, r.value.toStates(), EpochMillis(clock())))
                }
                is PatchbayResult.Err -> {
                    failures++
                    val reach = if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED
                    emit(DeviceSnapshot(device, reach, emptyList(), EpochMillis(clock())))
                    // Never hammer an unreachable device: exponential back-off, capped.
                    backoff = min(backoff * 2, config.maxBackoffMs)
                }
            }
            delay(if (failures == 0) config.pollIntervalMs else backoff)
        }
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        val s = socket ?: return PatchbayResult.Err(PatchbayError.Unreachable("Govee adapter not started"))
        val addr = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown Govee device"))
        val payload = when (command) {
            is Command.SetPower -> GoveeProtocol.turn(command.on)
            is Command.SetBrightness -> GoveeProtocol.brightness(command.level.value.toInt().coerceIn(1, 100))
            is Command.SetColour -> GoveeProtocol.colour(command.colour)
            is Command.SetColourTemperature -> {
                val k = command.kelvin.value.coerceIn(GoveeProtocol.MIN_KELVIN, GoveeProtocol.MAX_KELVIN)
                GoveeProtocol.colourTemperature(Kelvin(k))
            }
            else -> return PatchbayResult.Err(PatchbayError.Unsupported("Govee lights don't support ${command::class.simpleName}"))
        }
        return paced(addr) { s.send(addr, config.commandPort, payload) }
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        if (known[device] == null) return AdapterHealth(HealthState.UNPAIRED, null, "not discovered yet")
        val ok = status(device) is PatchbayResult.Ok
        val last = lastContact[device]?.let(::EpochMillis)
        return AdapterHealth(if (ok) HealthState.OK else HealthState.UNREACHABLE, last, null)
    }

    /** One devStatus round trip. Replies are matched by UDP source address. */
    public suspend fun status(device: DeviceId): PatchbayResult<GoveeProtocol.Message.Status> {
        val s = socket ?: return PatchbayResult.Err(PatchbayError.Unreachable("Govee adapter not started"))
        val addr = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown Govee device"))
        val waiter = CompletableDeferred<GoveeProtocol.Message.Status>()
        pendingStatus[addr]?.cancel()
        pendingStatus[addr] = waiter
        val sent = paced(addr) { s.send(addr, config.commandPort, GoveeProtocol.devStatus) }
        if (sent is PatchbayResult.Err) {
            pendingStatus.remove(addr, waiter)
            return sent
        }
        val reply = withTimeoutOrNull(config.statusTimeoutMs) { waiter.await() }
        pendingStatus.remove(addr, waiter)
        return if (reply != null) {
            lastContact[device] = clock()
            PatchbayResult.Ok(reply)
        } else {
            PatchbayResult.Err(PatchbayError.Timeout("Govee device didn't answer devStatus"))
        }
    }

    private suspend fun <T> paced(to: LanAddress, block: suspend () -> T): T = sendLock.withLock {
        val wait = (lastSend[to] ?: 0L) + config.minCommandGapMs - clock()
        if (wait > 0) delay(wait)
        lastSend[to] = clock()
        block()
    }

    override fun close() {
        receiver?.cancel()
        socket?.close()
        socket = null
    }

    public companion object {
        public val ID: AdapterId = AdapterId("govee-lan")

        public fun deviceIdFor(key: String): DeviceId = DeviceId("govee-$key")

        /** The LAN API exposes the same controls on every LAN-capable model. */
        public val CAPABILITIES: List<Capability> = listOf(
            Capability.Power,
            Capability.Brightness(),
            Capability.ColourTemperature(Kelvin(GoveeProtocol.MIN_KELVIN), Kelvin(GoveeProtocol.MAX_KELVIN)),
            Capability.RgbColour,
        )
    }
}
