package dev.patchbay.core.adapter.rtsp

import dev.patchbay.core.adapter.AdapterHealth
import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.ManualAdd
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.RecentProblems
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.model.StreamProtocol
import dev.patchbay.core.net.LanAddress
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.random.Random

/**
 * RTSP cameras that expose a local stream, such as eufy cameras with "RTSP stream" switched on
 * (docs/protocols/eufy.md). Cameras don't advertise RTSP, so they are added by address (manual add). Pairing asks
 * for the username and password set in the camera's app and proves them with a real `DESCRIBE`.
 *
 * Patchbay checks that the stream is up; it does not pull video yet (the player comes later). RTSP is
 * cleartext on the LAN, so credentials go only to the camera's own LAN address, with Digest when the camera
 * offers it. They are stored encrypted and never logged or put in a URL we display.
 */
public class RtspCameraAdapter(
    private val netGuard: NetGuard,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceAdapter, dev.patchbay.core.adapter.StreamProvider {

    public data class Config(
        val port: Int = RtspProtocol.PORT,
        /** eufy's documented path is `live0`. */
        val path: String = "live0",
        val connectTimeoutMs: Int = 3_000,
        val pollIntervalMs: Long = 15_000,
        val maxBackoffMs: Long = 120_000,
    )

    override val id: AdapterId = ID
    override val systemName: String = "RTSP cameras"
    override val discoverable: Boolean = false
    override val requiresPairing: Boolean = true
    override val manualAdd: ManualAdd = ManualAdd(ID, "RTSP camera (eufy)", "Name for this camera (e.g. Front door)")

    private class Known(val address: LanAddress, val credentials: Credentials?)
    private class Credentials(val user: String, val password: String)

    private val known = ConcurrentHashMap<DeviceId, Known>()
    private val lastContact = ConcurrentHashMap<DeviceId, Long>()

    /** Responses rejected as out of spec (diagnostics only). */
    @Volatile public var rejectedMessages: Long = 0
        private set

    private val problems = RecentProblems()
    private val probes = ConcurrentHashMap<LanAddress, String>()

    /** Cameras that have offered Digest login during this run; they are not sent a Basic (plain-text) login afterwards. */
    private val digestSeen = ConcurrentHashMap.newKeySet<LanAddress>()

    private fun rejected(why: String) {
        rejectedMessages++
        problems.add(why)
    }

    override fun diagnostics(device: DeviceId): List<String> = buildList {
        val k = known[device]
        add("login stored: ${k?.credentials != null}; stream path configured: /${config.path}")
        k?.address?.let(probes::get)?.let { add("last check of the stream: $it") }
    }

    override fun adapterDiagnostics(): List<String> = listOf("responses rejected as out of spec: $rejectedMessages") + problems.list().map { "problem: $it" }

    init {
        require(RtspProtocol.isSafePath(config.path)) { "unsafe stream path" }
    }

    override fun discover(): Flow<DiscoveredDevice> = emptyFlow() // RTSP isn't discoverable: add by address

    override fun manualDevice(address: String, key: String, choice: String?): PatchbayResult<DiscoveredDevice> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val name = key.trim().replace(Regex("\\s+"), " ")
        if (name.length !in 1..32 || !name.all { it.isLetterOrDigit() || it == ' ' || it == '-' }) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("Give the camera a short name using letters, numbers, spaces or hyphens"))
        }
        return PatchbayResult.Ok(
            DiscoveredDevice(
                adapter = ID, key = a.literal.map { if (it.isLetterOrDigit()) it else '-' }.joinToString(""), address = a.literal,
                port = config.port, kind = DeviceKind.CAMERA, vendor = "RTSP", model = "camera", displayName = name,
                controlPath = ControlPath.LOCAL_AFTER_SETUP, seenAt = EpochMillis(clock()),
            ),
        )
    }

    override fun deviceId(found: DiscoveredDevice): DeviceId = DeviceId("rtsp-${found.key.lowercase()}")

    override fun capabilities(device: DeviceId): List<Capability> = listOf(Capability.CameraStream(StreamProtocol.RTSP))

    override fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit> {
        val a = when (val r = netGuard.address(address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        known[device] = Known(a, pairing?.secret?.let(::decode))
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing> {
        val addr = when (val r = netGuard.address(device.address)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        val user = prompt.askForCode("RTSP username (camera settings → RTSP stream in the eufy app)", 1..64)
            ?: return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        val password = prompt.askForCode("RTSP password", 1..64)
            ?: return PatchbayResult.Err(PatchbayError.Cancelled("pairing cancelled"))
        if (!RtspProtocol.isSafeCredential(user) || !RtspProtocol.isSafeCredential(password)) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("The username or password has characters Patchbay can't send safely (spaces, quotes or backslashes)"))
        }
        val creds = Credentials(user, password)
        return when (val r = probe(addr, creds)) {
            is PatchbayResult.Err -> r
            is PatchbayResult.Ok -> {
                val id = deviceId(device)
                known[id] = Known(addr, creds)
                PatchbayResult.Ok(Pairing(id, encode(creds), pinnedFingerprint = null))
            }
        }
    }

    override fun observe(device: DeviceId): Flow<DeviceSnapshot> = flow {
        var failures = 0
        var backoff = config.pollIntervalMs
        while (currentCoroutineContext().isActive) {
            val k = known[device]
            val ok = k != null && probe(k.address, k.credentials) is PatchbayResult.Ok
            if (ok) {
                failures = 0
                backoff = config.pollIntervalMs
                lastContact[device] = clock()
                emit(DeviceSnapshot(device, Reachability.ONLINE, emptyList(), EpochMillis(clock())))
            } else {
                failures++
                emit(DeviceSnapshot(device, if (failures >= 3) Reachability.OFFLINE else Reachability.DEGRADED, emptyList(), EpochMillis(clock())))
                backoff = min(backoff * 2, config.maxBackoffMs)
            }
            delay(if (failures == 0) config.pollIntervalMs else backoff)
        }
    }

    /** The playable URL with the login in its user-info, percent-encoded. Only for paired cameras. */
    override fun streamLease(device: DeviceId): PatchbayResult<dev.patchbay.core.adapter.StreamLease> {
        val k = known[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown camera"))
        val c = k.credentials ?: return PatchbayResult.Err(PatchbayError.Unauthorized("enter the camera's login first"))
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val host = if (':' in k.address.literal) "[${k.address.literal}]" else k.address.literal
        val url = "rtsp://${enc(c.user)}:${enc(c.password)}@$host:${config.port}/${config.path}"
        return PatchbayResult.Ok(dev.patchbay.core.adapter.StreamLease(url, StreamProtocol.RTSP))
    }

    override suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit> =
        PatchbayResult.Err(PatchbayError.Unsupported("cameras are view-only in Patchbay"))

    override suspend fun health(device: DeviceId): AdapterHealth {
        val k = known[device] ?: return AdapterHealth(HealthState.UNPAIRED, null, "not added yet")
        if (k.credentials == null) return AdapterHealth(HealthState.UNPAIRED, null, "needs pairing")
        val ok = probe(k.address, k.credentials) is PatchbayResult.Ok
        return AdapterHealth(if (ok) HealthState.OK else HealthState.UNREACHABLE, lastContact[device]?.let(::EpochMillis), null)
    }

    /** DESCRIBE once, answering a 401 challenge if we have credentials. Never sends them without a challenge. */
    private suspend fun probe(addr: LanAddress, creds: Credentials?): PatchbayResult<Unit> {
        val host = if (':' in addr.literal) "[${addr.literal}]" else addr.literal
        val uri = "rtsp://$host:${config.port}/${config.path}"
        val first = when (val r = request(addr, RtspProtocol.describe(uri, 1))) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { probes[addr] = "no usable answer (${r.error.message.filter { it.code in 32..126 }.take(60)})"; return r }
        }
        return when (first.status) {
            200 -> { probes[addr] = "RTSP 200 without asking for a login"; PatchbayResult.Ok(Unit) }
            401 -> {
                val scheme = first.header("www-authenticate").firstOrNull()?.trim()?.takeWhile { it.isLetter() }?.take(12)?.ifEmpty { null } ?: "none offered"
                if (creds == null) { probes[addr] = "RTSP 401, login method $scheme, no login stored"; return PatchbayResult.Err(PatchbayError.Unauthorized("the camera needs a username and password")) }
                val challenge = RtspProtocol.parseChallenge(first.header("www-authenticate"))
                    ?: run { probes[addr] = "RTSP 401, login method $scheme is not supported"; problems.add("login method not supported: $scheme"); return PatchbayResult.Err(PatchbayError.Unsupported("the camera offered no login method Patchbay supports")) }
                // Basic sends the password as plain text. A camera that offered Digest and now offers Basic is either reconfigured or someone on the network answering for it:
                // either way the password is not sent that way.
                if (challenge is RtspProtocol.Challenge.Digest) digestSeen += addr
                else if (addr in digestSeen) {
                    probes[addr] = "RTSP 401, Basic offered after Digest: refused"
                    problems.add("a camera that used a secure login now asks for a plain-text one; Patchbay did not send the password")
                    return PatchbayResult.Err(PatchbayError.Unauthorized("the camera now asks for a plain-text login after using a secure one, so Patchbay did not send your password"))
                }
                val cnonce = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
                val auth = RtspAuth.authorization(challenge, creds.user, creds.password, "DESCRIBE", uri, cnonce)
                val second = when (val r = request(addr, RtspProtocol.describe(uri, 2, auth))) {
                    is PatchbayResult.Ok -> r.value
                    is PatchbayResult.Err -> { probes[addr] = "RTSP 401 ($scheme), then no usable answer"; return r }
                }
                probes[addr] = "RTSP 401 ($scheme), then ${second.status.coerceIn(0, 999)}"
                when (second.status) {
                    200 -> PatchbayResult.Ok(Unit)
                    401 -> PatchbayResult.Err(PatchbayError.Unauthorized("the camera rejected the username or password"))
                    404 -> notFound()
                    else -> PatchbayResult.Err(PatchbayError.Unreachable("the camera answered RTSP ${second.status}"))
                }
            }
            404 -> { probes[addr] = "RTSP 404 (no stream at /${config.path})"; notFound() }
            else -> { probes[addr] = "RTSP ${first.status.coerceIn(0, 999)}"; PatchbayResult.Err(PatchbayError.Unreachable("the camera answered RTSP ${first.status}")) }
        }
    }

    private fun notFound(): PatchbayResult.Err =
        PatchbayResult.Err(PatchbayError.Unreachable("the camera has no stream at /${config.path}; is RTSP switched on for this camera?"))

    /** Sends one request on a fresh connection and reads only the header block. */
    private suspend fun request(addr: LanAddress, bytes: ByteArray): PatchbayResult<RtspProtocol.Response> {
        val socket = when (val c = netGuard.connectTcp(addr, config.port, config.connectTimeoutMs)) {
            is PatchbayResult.Ok -> c.value
            is PatchbayResult.Err -> return c
        }
        return withContext(Dispatchers.IO) {
            try {
                socket.use {
                    it.getOutputStream().apply { write(bytes); flush() }
                    val input = it.getInputStream().buffered()
                    val buf = java.io.ByteArrayOutputStream()
                    var tail = 0
                    while (true) {
                        val b = input.read()
                        if (b < 0) return@use PatchbayResult.Err(PatchbayError.Unreachable("the camera closed the connection"))
                        buf.write(b)
                        tail = ((tail shl 8) or b) // last four bytes
                        if (tail == 0x0D0A0D0A) break
                        if (buf.size() > RtspProtocol.MAX_HEADER_BYTES) {
                            rejected("header block too large")
                            return@use PatchbayResult.Err(PatchbayError.ProtocolViolation("rtsp: header block too large"))
                        }
                    }
                    RtspProtocol.parseResponse(buf.toByteArray()).also { r -> if (r is PatchbayResult.Err) rejected(r.error.message) }
                }
            } catch (e: SocketTimeoutException) {
                problems.add("no answer from the camera")
                PatchbayResult.Err(PatchbayError.Timeout("the camera didn't answer"))
            } catch (e: IOException) {
                problems.add("connection failed: ${e.javaClass.simpleName}")
                PatchbayResult.Err(PatchbayError.Unreachable("camera connection failed: ${e.javaClass.simpleName}"))
            }
        }
    }

    private fun encode(c: Credentials): ByteArray = "${c.user}\n${c.password}".toByteArray(Charsets.UTF_8)

    private fun decode(secret: ByteArray): Credentials? {
        val parts = secret.toString(Charsets.UTF_8).split('\n')
        return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) Credentials(parts[0], parts[1]) else null
    }

    public companion object {
        public val ID: AdapterId = AdapterId("rtsp-camera")
    }
}
