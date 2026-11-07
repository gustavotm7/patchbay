package dev.patchbay.core.adapter.webos

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.discovery.Ssdp
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.MediaAction
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.net.WakeOnLan
import dev.patchbay.core.net.tls.PinnedTls
import dev.patchbay.core.net.ws.WebSocketClient
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * LG webOS TVs over SSAP (docs/protocols/lg-webos.md).
 *
 * - **TLS only** (`wss://:3001`), with the TV's self-signed key pinned on first use (ADR 0005). The legacy
 *   cleartext `ws://:3000` is never used, so the client key never crosses the LAN in the clear.
 * - Pairing needs the user twice: confirm the fingerprint in Patchbay, then accept the prompt on the TV.
 * - Power-on uses Wake-on-LAN. The TV's MAC is read over the authenticated connection, so the TV must have been
 *   reached once this session, and "Turn on via Wi-Fi" must be enabled on the TV.
 */
public class WebOsAdapter(
    private val netGuard: NetGuard,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter, AutoCloseable {

    public data class Config(
        val ssdpTargets: List<String> = listOf(Ssdp.GROUP_V4),
        val ssdpPort: Int = Ssdp.PORT,
        val wssPort: Int = Ssap.WSS_PORT,
        val scanWindowMs: Long = 2_500,
        val replyTimeoutMs: Int = 4_000,
        val pairingTimeoutMs: Int = 60_000,
        val pollIntervalMs: Long = 5_000,
        val maxBackoffMs: Long = 60_000,
        val bindLoopbackOnly: Boolean = false,
        /** Where magic packets go: LAN broadcast plus the TV's own address. */
        val wolTargets: List<String> = listOf(WakeOnLan.BROADCAST),
        val wolPort: Int = WakeOnLan.PORT,
    )

    override val id: AdapterId = ID
    override fun trustChanged(device: DeviceId): Boolean = device in trustBroken
    override val systemName: String = "LG TVs"
    override val discoveryNote: String = "The TV must be on, on the same network, with “LG Connect Apps” (or “Mobile TV On”) enabled in its settings."
    override val requiresPairing: Boolean = true

    private class Known(val address: LanAddress, val clientKey: String?, val pin: String?, val name: String)

    private class Session(val ws: WebSocketClient) {
        var nextId = 1
        val lock = Mutex()
        var inputs: List<Pair<String, String>> = emptyList()
    }

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val sessions = ConcurrentHashMap<DeviceId, Session>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()
    private val trustBroken = ConcurrentHashMap.newKeySet<DeviceId>()
    private val macs = ConcurrentHashMap<DeviceId, ByteArray>()

    override fun discover(): Flow<DiscoveredDevice> = channelFlow {
        val sock = (netGuard.openUdp(0, config.bindLoopbackOnly) as? PatchbayResult.Ok)?.value ?: return@channelFlow
        sock.use { s ->
            for (t in config.ssdpTargets) {
                val a = (netGuard.address(t) as? PatchbayResult.Ok)?.value ?: continue
                s.send(a, config.ssdpPort, Ssdp.search(Ssap.SSDP_ST))
            }
            val seen = HashSet<String>()
            val deadline = System.nanoTime() + config.scanWindowMs * 1_000_000
            while (currentCoroutineContext().isActive) {
                val left = ((deadline - System.nanoTime()) / 1_000_000).toInt()
                if (left <= 0) break
                val d = (s.receive(4_096, left) as? PatchbayResult.Ok)?.value ?: continue
                val reply = (Ssdp.parse(d.payload) as? PatchbayResult.Ok)?.value ?: continue
                if (reply.st?.contains("webos-second-screen") != true) continue
                val key = reply.usn?.substringAfter("uuid:")?.substringBefore("::")?.filter { it.isLetterOrDigit() || it == '-' }?.take(48)
                    ?.takeIf { it.isNotEmpty() } ?: d.from.literal.replace('.', '-').replace(':', '-')
                if (!seen.add(key)) continue
                known.putIfAbsent(DeviceId("lg-webos-$key"), Known(d.from, null, null, "LG TV"))
                send(
                    DiscoveredDevice(
                        adapter = ID, key = key, address = d.from.literal, port = config.wssPort, kind = DeviceKind.TV,
                        vendor = "LG", model = "webOS TV", displayName = null,
                        controlPath = ControlPath.LOCAL, seenAt = EpochMillis(clock()),
                    ),
                )
            }
        }
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = DeviceId("lg-webos-${found.key}")

    override fun capabilities(device: DeviceId): List<Capability> = listOf(
        Capability.Power,
        Capability.Volume(maximum = 100, supportsMute = true),
        Capability.InputSource(sessions[device]?.inputs?.map { it.second } ?: emptyList()),
        Capability.MediaTransport(setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.STOP)),
    )

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        // Secret = client key, optionally followed by "\n" + the TV's MAC (learned at pairing, for Wake-on-LAN).
        val parts = pairing?.secret?.decodeToString()?.split('\n')
        parts?.getOrNull(1)?.let(WakeOnLan::parseMac)?.let { macs[device] = it }
        known[device] = Known(a, parts?.firstOrNull(), pairing?.pinnedFingerprint, "LG TV")
        sessions.remove(device)?.ws?.close()
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val id = deviceId(device)
        val addr = when (val r = netGuard.address(device.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val tls = when (val r = netGuard.connectTls(addr, config.wssPort, pinnedSha256 = null, deviceName = "TV", timeoutMs = config.replyTimeoutMs)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        // 1. The user confirms the TV's key before we send anything that matters.
        if (!prompt.confirmFingerprint(device.model ?: "LG TV", PinnedTls.display(tls.spkiSha256))) {
            tls.close()
            return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        }
        val ws = when (val r = withContext(Dispatchers.IO) { WebSocketClient.handshake(tls.socket, "${addr.literal}:${config.wssPort}") }) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { tls.close(); return r }
        }
        return withContext(Dispatchers.IO) {
            try {
                ws.sendText(Ssap.hello())
                expect<Ssap.Incoming.Hello>(ws) ?: return@withContext err("TV didn't say hello")
                ws.sendText(Ssap.register(null))
                // 2. The TV shows its own prompt; tell the user, then wait (up to a minute) for them to accept.
                tls.socket.soTimeout = config.pairingTimeoutMs
                when (val first = next(ws)) {
                    is Ssap.Incoming.PromptShown -> prompt.instruct("Accept the “Patchbay” request on your TV")
                    is Ssap.Incoming.Registered -> return@withContext PatchbayResult.Ok(Pairing(id, secretFor(ws, first.clientKey), tls.spkiSha256))
                    is Ssap.Incoming.Error -> return@withContext PatchbayResult.Err(PatchbayError.Unauthorized("TV refused pairing: ${first.message}"))
                    else -> return@withContext err("unexpected pairing reply")
                }
                when (val second = next(ws)) {
                    is Ssap.Incoming.Registered -> PatchbayResult.Ok(Pairing(id, secretFor(ws, second.clientKey), tls.spkiSha256))
                    is Ssap.Incoming.Error -> PatchbayResult.Err(PatchbayError.Unauthorized("pairing was declined on the TV"))
                    null -> PatchbayResult.Err(PatchbayError.Timeout("no answer on the TV"))
                    else -> err("unexpected pairing reply")
                }
            } finally {
                ws.close()
            }
        }
    }

    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = config.pollIntervalMs
        while (currentCoroutineContext().isActive) {
            val states = poll(device)
            if (states is PatchbayResult.Ok) {
                failures = 0; backoff = config.pollIntervalMs
                lastContact[device] = clock()
                emit(DeviceSnapshot(device, Reachability.ONLINE, states.value, EpochMillis(clock())))
            } else {
                failures++
                sessions.remove(device)?.ws?.close()
                // A TV in standby often drops the socket: report OFFLINE after a few misses, never hammer it.
                emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                backoff = min(backoff * 2, config.maxBackoffMs)
            }
            delay(if (failures == 0) config.pollIntervalMs else backoff)
        }
    }

    private suspend fun poll(device: DeviceId): PatchbayResult<List<CapabilityState>> {
        val s = when (val r = session(device)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val power = request(s, "com.webos.service.tvpower/power/getPowerState")
        val volume = request(s, "audio/getVolume")
        if (s.inputs.isEmpty()) (request(s, "tv/getExternalInputList") as? PatchbayResult.Ok)?.let { s.inputs = Ssap.inputs(it.value) }
        val states = buildList {
            // Older firmware lacks getPowerState; if we can talk to it, it's on.
            add(CapabilityState.PowerState((power as? PatchbayResult.Ok)?.value?.let(Ssap::powerOn) ?: true))
            (volume as? PatchbayResult.Ok)?.value?.let(Ssap::volume)?.let { add(CapabilityState.VolumeState(it.level, it.muted)) }
        }
        return if (volume is PatchbayResult.Err && power is PatchbayResult.Err) volume else PatchbayResult.Ok(states)
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        // A TV in standby has no session to talk to: waking it is a broadcast, not an SSAP call.
        if (command is Command.SetPower && command.on) return wake(device)
        val s = when (val r = session(device)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val empty = JsonObject(emptyMap())
        // Payloads are built structurally: values that came from the TV (input IDs) can't inject JSON.
        val (uri, payload) = when (command) {
            is Command.SetVolume -> "audio/setVolume" to buildJsonObject { put("volume", command.level) }
            is Command.SetMute -> "audio/setMute" to buildJsonObject { put("mute", command.muted) }
            is Command.SetPower -> if (command.on) return wake(device) else "system/turnOff" to empty
            is Command.SelectInput -> {
                val inputId = s.inputs.firstOrNull { it.second == command.source || it.first == command.source }?.first
                    ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("unknown input"))
                "tv/switchInput" to buildJsonObject { put("inputId", inputId) }
            }
            is Command.Media -> when (command.action) {
                MediaAction.PLAY -> "media.controls/play" to empty
                MediaAction.PAUSE -> "media.controls/pause" to empty
                MediaAction.STOP -> "media.controls/stop" to empty
                else -> return PatchbayResult.Err(PatchbayError.Unsupported("${command.action} not supported"))
            }
            else -> return PatchbayResult.Err(PatchbayError.Unsupported("TVs don't support ${command::class.simpleName}"))
        }
        return when (val r = request(s, uri, payload)) {
            is PatchbayResult.Ok -> PatchbayResult.Ok(Unit)
            is PatchbayResult.Err -> r
        }
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        val k = known[device] ?: return AdapterHealth(HealthState.UNPAIRED, null, "not discovered yet")
        if (k.clientKey == null) return AdapterHealth(HealthState.UNPAIRED, null, "pair to control")
        if (device in trustBroken) return AdapterHealth(HealthState.TRUST_CHANGED, lastContact[device]?.let(::EpochMillis), "TV key changed")
        return AdapterHealth(if (session(device) is PatchbayResult.Ok) HealthState.OK else HealthState.UNREACHABLE, lastContact[device]?.let(::EpochMillis), null)
    }

    /** One authenticated connection per TV, reused across polls and commands. */
    private suspend fun session(device: DeviceId): PatchbayResult<Session> {
        sessions[device]?.let { return PatchbayResult.Ok(it) }
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown TV"))
        val key = k.clientKey ?: return PatchbayResult.Err(PatchbayError.Unauthorized("TV not paired"))
        val tls = when (val r = netGuard.connectTls(k.address, config.wssPort, k.pin, k.name, config.replyTimeoutMs)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> {
                if (r.error is PatchbayError.TrustChanged) trustBroken += device
                return r
            }
        }
        trustBroken -= device
        return withContext(Dispatchers.IO) {
            val ws = when (val r = WebSocketClient.handshake(tls.socket, "${k.address.literal}:${config.wssPort}")) {
                is PatchbayResult.Ok -> r.value
                is PatchbayResult.Err -> { tls.close(); return@withContext r }
            }
            ws.sendText(Ssap.hello())
            if (expect<Ssap.Incoming.Hello>(ws) == null) { ws.close(); return@withContext err("TV didn't say hello") }
            ws.sendText(Ssap.register(key))
            if (expect<Ssap.Incoming.Registered>(ws) == null) {
                ws.close()
                return@withContext PatchbayResult.Err(PatchbayError.Unauthorized("the TV no longer accepts this pairing; pair again"))
            }
            val s = Session(ws)
            sessions[device] = s
            // Learn the MAC for Wake-on-LAN while we're authenticated.
            (request(s, "com.webos.service.connectionmanager/getinfo") as? PatchbayResult.Ok)?.value
                ?.let(Ssap::macAddress)?.let(WakeOnLan::parseMac)?.let { macs[device] = it }
            PatchbayResult.Ok(s)
        }
    }

    private suspend fun request(s: Session, uri: String, payload: JsonObject = JsonObject(emptyMap())): PatchbayResult<JsonObject> =
        s.lock.withLock {
            withContext(Dispatchers.IO) {
                val id = "pb_${s.nextId++}"
                (s.ws.sendText(Ssap.request(id, uri, payload)) as? PatchbayResult.Err)?.let { return@withContext it }
                // Replies arrive in order on this connection; skip anything that isn't ours (bounded).
                repeat(8) {
                    when (val m = next(s.ws)) {
                        is Ssap.Incoming.Response -> if (m.id == id) return@withContext PatchbayResult.Ok(m.payload)
                        is Ssap.Incoming.Error -> if (m.id == id) return@withContext PatchbayResult.Err(PatchbayError.Unsupported("TV: ${m.message}"))
                        null -> return@withContext PatchbayResult.Err(PatchbayError.Unreachable("TV closed the connection"))
                        else -> {}
                    }
                }
                err("no reply for $uri")
            }
        }

    /** While still authenticated at pairing, also ask for the MAC so power-on works after a restart. */
    private fun secretFor(ws: WebSocketClient, clientKey: String): ByteArray {
        val mac = runCatching {
            ws.sendText(Ssap.request("pb_mac", "com.webos.service.connectionmanager/getinfo"))
            (next(ws) as? Ssap.Incoming.Response)?.payload?.let(Ssap::macAddress)
        }.getOrNull()
        return (if (mac != null) "$clientKey\n$mac" else clientKey).encodeToByteArray()
    }

    private suspend fun wake(device: DeviceId): PatchbayResult<Unit> {
        val mac = macs[device] ?: return PatchbayResult.Err(
            PatchbayError.Unsupported("Patchbay hasn't learned this TV's MAC yet: turn it on once with the remote while Patchbay is running"),
        )
        val sock = when (val r = netGuard.openUdp(0, config.bindLoopbackOnly)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        sock.use { u ->
            val targets = (config.wolTargets + listOfNotNull(known[device]?.address?.literal)).distinct()
            var sent = false
            for (t in targets) {
                val a = (netGuard.address(t) as? PatchbayResult.Ok)?.value ?: continue
                if (u.send(a, config.wolPort, WakeOnLan.packet(mac)) is PatchbayResult.Ok) sent = true
            }
            return if (sent) PatchbayResult.Ok(Unit) else PatchbayResult.Err(PatchbayError.Unreachable("couldn't send the wake-up packet"))
        }
    }

    private fun next(ws: WebSocketClient): Ssap.Incoming? {
        val text = (ws.receiveText() as? PatchbayResult.Ok)?.value ?: return null
        return (Ssap.parse(text) as? PatchbayResult.Ok)?.value
    }

    private inline fun <reified T : Ssap.Incoming> expect(ws: WebSocketClient): T? = next(ws) as? T

    private fun err(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("webos: $why"))

    override fun close() {
        sessions.values.forEach { it.ws.close() }
        sessions.clear()
    }

    public companion object {
        public val ID: AdapterId = AdapterId("lg-webos")
    }
}
