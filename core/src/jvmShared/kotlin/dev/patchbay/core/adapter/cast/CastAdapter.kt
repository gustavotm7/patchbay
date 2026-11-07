package dev.patchbay.core.adapter.cast

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.discovery.Finding
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
import dev.patchbay.core.model.PlaybackStatus
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.net.tls.PinnedTls
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Chromecast, Google TV and Cast speakers over Cast v2: TLS on 8009 with a trust-on-first-use key pin (ADR 0005),
 * heartbeat, receiver status (volume, running app) and media status. Controls: volume, mute, play, pause, stop.
 * Nothing is cast to the device and no app is launched. The device-auth certificate chain is deliberately not
 * validated (docs/protocols/cast.md): the pin is the trust anchor, and Google's CA expiry can't break us.
 *
 * Devices come from the network census (mDNS `_googlecast._tcp`) through [CastFinder].
 */
public class CastAdapter(
    private val netGuard: NetGuard,
    private val finder: CastFinder,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter {

    public fun interface CastFinder {
        public suspend fun find(): List<Finding>
    }

    public data class Config(
        val port: Int = CastProtocol.PORT,
        val connectTimeoutMs: Int = 5_000,
        /** Read timeout: when nothing arrives, send a PING; three silent intervals in a row drop the session. */
        val heartbeatMs: Int = 5_000,
        val minEmitGapMs: Long = 250,
        val maxBackoffMs: Long = 60_000,
    )

    override val id: AdapterId = ID
    override val requiresPairing: Boolean = true
    override val systemName: String = "Cast and Google TV"
    override val discoveryNote: String =
        "Chromecasts, Google TVs and speakers with Cast built in must be on and on the same network. A guest network or a VLAN hides them."

    private class Known(val address: LanAddress, val pin: String?, val name: String)

    private class Session(val tls: dev.patchbay.core.net.TlsConnection, val out: OutputStream) {
        @Volatile var transportId: String? = null
        @Volatile var mediaSessionId: Int? = null
        @Volatile var volume: Double? = null
        @Volatile var muted: Boolean? = null
        @Volatile var appName: String? = null
        @Volatile var media: CastProtocol.MediaStatus? = null
        fun send(m: CastProtocol.Message) {
            synchronized(this) { out.write(CastProtocol.frame(m)); out.flush() }
        }
    }

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val sessions = ConcurrentHashMap<DeviceId, Session>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()
    private val trustBroken = ConcurrentHashMap.newKeySet<DeviceId>()
    private val requestIds = AtomicInteger(1)

    /** Messages rejected as out of spec (diagnostics only). */
    @Volatile public var rejectedMessages: Long = 0
        private set

    override fun discover(): Flow<DiscoveredDevice> = flow {
        for (f in finder.find()) {
            if (f.services.none { it.startsWith("_googlecast._tcp") }) continue
            val model = f.model?.take(32)
            emit(
                DiscoveredDevice(
                    adapter = ID, key = keyFor(f.address), address = f.address, port = config.port,
                    kind = kindFor(model), vendor = "Google Cast", model = model ?: "Cast device",
                    displayName = f.friendlyName?.take(48)?.takeIf { it.isNotBlank() },
                    controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
                ),
            )
        }
    }

    private fun keyFor(address: String): String = "ip-" + address.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

    /** Audio-only models are speakers; everything else is a media receiver (Chromecast, Google TV, Cast TVs). */
    private fun kindFor(model: String?): DeviceKind {
        val m = model?.lowercase() ?: return DeviceKind.MEDIA_RECEIVER
        return if (listOf("mini", "home", "audio", "speaker", "nest hub", "max").any { it in m } && "tv" !in m) DeviceKind.SPEAKER else DeviceKind.MEDIA_RECEIVER
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = DeviceId("cast-${found.key.lowercase()}")

    override fun capabilities(device: DeviceId): List<Capability> = CAPABILITIES

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        known[device] = Known(a, pairing?.pinnedFingerprint, known[device]?.name ?: "Cast device")
        return PatchbayResult.Ok(Unit)
    }

    /** Pairing is the fingerprint check only: there is no code. The pinned key is what later connections must present. */
    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val addr = when (val r = netGuard.address(device.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val name = device.displayName ?: "Cast device"
        val tls = when (val r = netGuard.connectTls(addr, config.port, pinnedSha256 = null, deviceName = name, timeoutMs = config.connectTimeoutMs)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val confirmed = prompt.confirmFingerprint(name, PinnedTls.display(tls.spkiSha256))
        tls.close()
        if (!confirmed) return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        val id = deviceId(device)
        known[id] = Known(addr, tls.spkiSha256, name)
        return PatchbayResult.Ok(Pairing(id, ByteArray(0), tls.spkiSha256))
    }

    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            val session = when (val r = connect(device)) {
                is PatchbayResult.Ok -> r.value
                is PatchbayResult.Err -> {
                    failures++
                    emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                    backoff = min(backoff * 2, config.maxBackoffMs)
                    delay(backoff)
                    continue
                }
            }
            failures = 0; backoff = 2_000L
            val input = DataInputStream(session.tls.socket.getInputStream())
            var silent = 0
            var lastEmit = 0L
            var gotStatus = false
            try {
                while (currentCoroutineContext().isActive) {
                    val message = try {
                        readMessage(input)
                    } catch (e: SocketTimeoutException) {
                        if (++silent >= 3) break
                        session.send(CastProtocol.message(CastProtocol.RECEIVER, CastProtocol.NS_HEARTBEAT, CastProtocol.PING))
                        continue
                    }
                    silent = 0
                    val m = message ?: continue
                    lastContact[device] = clock()
                    val changed = handle(session, m)
                    if (changed && (!gotStatus || clock() - lastEmit >= config.minEmitGapMs)) {
                        gotStatus = true
                        lastEmit = clock()
                        emit(DeviceSnapshot(device, Reachability.ONLINE, statesOf(session), EpochMillis(clock())))
                    }
                    if (CastProtocol.messageType(m.payload) == "CLOSE" && m.namespace == CastProtocol.NS_CONNECTION) break
                }
            } catch (e: IOException) {
                // fall through: the session ended; reconnect with back-off
            }
            sessions.remove(device)?.tls?.close()
            emit(DeviceSnapshot(device, Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
            delay(backoff)
        }
    }.flowOn(Dispatchers.IO)

    /** Reads one framed message. A frame that doesn't parse is counted and skipped; a bad length ends the session. */
    private fun readMessage(input: DataInputStream): CastProtocol.Message? {
        val header = ByteArray(4).also { input.readFully(it) }
        val n = when (val r = CastProtocol.frameLength(header)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { rejectedMessages++; throw IOException("bad frame length") }
        }
        val body = ByteArray(n).also { input.readFully(it) }
        return when (val r = CastProtocol.decode(body)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { if (r.error !is PatchbayError.Unsupported) rejectedMessages++; null }
        }
    }

    /** Applies one message to the session. Returns true when something worth showing changed. */
    private fun handle(s: Session, m: CastProtocol.Message): Boolean {
        when (m.namespace) {
            CastProtocol.NS_HEARTBEAT -> {
                if (CastProtocol.messageType(m.payload) == "PING") s.send(CastProtocol.message(CastProtocol.RECEIVER, CastProtocol.NS_HEARTBEAT, CastProtocol.PONG))
                return false
            }
            CastProtocol.NS_RECEIVER -> {
                val st = (CastProtocol.parseReceiverStatus(m.payload) as? PatchbayResult.Ok)?.value ?: run {
                    if (CastProtocol.messageType(m.payload) == "RECEIVER_STATUS") rejectedMessages++
                    return false
                }
                st.volume?.let { s.volume = it }
                st.muted?.let { s.muted = it }
                s.appName = st.appName
                val transport = st.transportId
                if (transport != s.transportId) {
                    s.transportId = transport
                    s.media = null; s.mediaSessionId = null
                    if (transport != null) {
                        s.send(CastProtocol.message(transport, CastProtocol.NS_CONNECTION, CastProtocol.CONNECT))
                        s.send(CastProtocol.message(transport, CastProtocol.NS_MEDIA, CastProtocol.getStatus(requestIds.getAndIncrement())))
                    }
                }
                return true
            }
            CastProtocol.NS_MEDIA -> {
                if (m.source != s.transportId) return false // only the app we connected to
                return when (val r = CastProtocol.parseMediaStatus(m.payload)) {
                    is PatchbayResult.Ok -> { s.media = r.value; s.mediaSessionId = r.value?.mediaSessionId; true }
                    is PatchbayResult.Err -> { if (CastProtocol.messageType(m.payload) == "MEDIA_STATUS") rejectedMessages++; false }
                }
            }
        }
        return false
    }

    private fun statesOf(s: Session): List<CapabilityState> = buildList {
        s.volume?.let { add(CapabilityState.VolumeState((it * 100).roundToInt().coerceIn(0, 100), s.muted ?: false)) }
        val media = s.media
        val status = when (media?.playerState) {
            "PLAYING" -> PlaybackStatus.PLAYING
            "PAUSED" -> PlaybackStatus.PAUSED
            "BUFFERING" -> PlaybackStatus.BUFFERING
            else -> PlaybackStatus.IDLE
        }
        add(
            CapabilityState.MediaState(
                status, media?.title ?: s.appName, media?.artist,
                media?.positionSec?.let { (it * 1000).toLong() }, media?.durationSec?.let { (it * 1000).toLong() },
            ),
        )
    }

    private suspend fun connect(device: DeviceId): PatchbayResult<Session> {
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown device"))
        val tls = when (val r = netGuard.connectTls(k.address, config.port, k.pin, k.name, config.connectTimeoutMs)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> {
                if (r.error is PatchbayError.TrustChanged) trustBroken += device
                return r
            }
        }
        trustBroken -= device
        return try {
            tls.socket.soTimeout = config.heartbeatMs
            val s = Session(tls, tls.socket.getOutputStream())
            s.send(CastProtocol.message(CastProtocol.RECEIVER, CastProtocol.NS_CONNECTION, CastProtocol.CONNECT))
            s.send(CastProtocol.message(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.getStatus(requestIds.getAndIncrement())))
            sessions[device] = s
            PatchbayResult.Ok(s)
        } catch (e: IOException) {
            tls.close()
            PatchbayResult.Err(PatchbayError.Unreachable("could not start a session: ${e.javaClass.simpleName}"))
        }
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> {
        val s = sessions[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("not connected to the device yet"))
        return try {
            when (command) {
                is Command.SetVolume -> {
                    s.send(CastProtocol.message(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.setVolume(command.level.coerceIn(0, 100) / 100.0, requestIds.getAndIncrement())))
                    PatchbayResult.Ok(Unit)
                }
                is Command.SetMute -> {
                    s.send(CastProtocol.message(CastProtocol.RECEIVER, CastProtocol.NS_RECEIVER, CastProtocol.setMuted(command.muted, requestIds.getAndIncrement())))
                    PatchbayResult.Ok(Unit)
                }
                is Command.Media -> {
                    val type = when (command.action) {
                        MediaAction.PLAY -> "PLAY"
                        MediaAction.PAUSE -> "PAUSE"
                        MediaAction.STOP -> "STOP"
                        else -> return PatchbayResult.Err(PatchbayError.Unsupported("only play, pause and stop are supported for Cast"))
                    }
                    val transport = s.transportId
                    val mediaId = s.mediaSessionId
                    if (transport == null || mediaId == null) return PatchbayResult.Err(PatchbayError.Unsupported("nothing is playing on this device"))
                    s.send(CastProtocol.message(transport, CastProtocol.NS_MEDIA, CastProtocol.mediaCommand(type, mediaId, requestIds.getAndIncrement())))
                    PatchbayResult.Ok(Unit)
                }
                else -> PatchbayResult.Err(PatchbayError.Unsupported("Cast devices accept volume, mute, play, pause and stop"))
            }
        } catch (e: IOException) {
            PatchbayResult.Err(PatchbayError.Unreachable("the connection to the device dropped"))
        }
    }

    override suspend fun health(device: DeviceId): AdapterHealth {
        val k = known[device] ?: return AdapterHealth(HealthState.UNPAIRED, null, "not discovered yet")
        val last = lastContact[device]?.let(::EpochMillis)
        return when {
            device in trustBroken -> AdapterHealth(HealthState.TRUST_CHANGED, last, "${k.name} presented a different key than the one you paired")
            sessions.containsKey(device) -> AdapterHealth(HealthState.OK, last, null)
            else -> AdapterHealth(HealthState.UNREACHABLE, last, null)
        }
    }

    public companion object {
        public val ID: AdapterId = AdapterId("cast-v2")

        public val CAPABILITIES: List<Capability> = listOf(
            Capability.Volume(maximum = 100, supportsMute = true),
            Capability.MediaTransport(setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.STOP)),
        )
    }
}
