package dev.patchbay.core.adapter.rtsp

import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import kotlin.random.Random

/**
 * Plays a camera's H.264 video over RTSP with RTP interleaved on the one TCP connection (RFC 2326 §10.12), through [NetGuard].
 * It delivers Annex-B NAL units to [stream]'s callback; decoding them is somebody else's job (ADR 0012). Credentials come from the
 * short-lived [StreamLease], are sent only after the camera's own 401 challenge (and remembered for later requests on the same
 * connection), and never logged. Everything the camera sends is size-bounded and strictly parsed.
 */
public class RtspStreamClient(
    private val netGuard: NetGuard,
    private val config: Config = Config(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    public data class Config(
        val connectTimeoutMs: Int = 4_000,
        /** Socket read timeout; also how often a cancelled stream notices it should stop. */
        val readTimeoutMs: Int = 500,
        /** No bytes at all for this long ends the stream. */
        val stallMs: Long = 10_000,
        val keepAliveMs: Long = 25_000,
    )

    /** Packets the depacketizer dropped over the last stream (diagnostics only). */
    @Volatile public var droppedPackets: Long = 0
        private set

    private class Target(val host: String, val port: Int, val path: String, val user: String?, val password: String?)

    private fun parse(lease: StreamLease): PatchbayResult<Target> {
        val uri = try { URI(lease.url) } catch (e: Exception) { return bad("not a valid stream address") }
        if (uri.scheme != "rtsp") return bad("only rtsp:// streams are supported")
        val host = uri.host?.removeSurrounding("[", "]") ?: return bad("no host")
        val port = if (uri.port == -1) RtspProtocol.PORT else uri.port
        val path = uri.rawPath?.removePrefix("/") ?: ""
        if (!RtspProtocol.isSafePath(path)) return bad("unsafe stream path")
        val info = uri.rawUserInfo
        var user: String? = null; var pass: String? = null
        if (info != null) {
            val i = info.indexOf(':')
            if (i < 0) return bad("incomplete login")
            user = URLDecoder.decode(info.substring(0, i).replace("+", "%2B"), "UTF-8")
            pass = URLDecoder.decode(info.substring(i + 1).replace("+", "%2B"), "UTF-8")
            if (!RtspProtocol.isSafeCredential(user) || !RtspProtocol.isSafeCredential(pass)) return bad("unsafe login characters")
        }
        return PatchbayResult.Ok(Target(host, port, path, user, pass))
    }

    private fun bad(why: String) = PatchbayResult.Err(PatchbayError.InvalidArgument("stream: $why"))

    /**
     * Runs until the coroutine is cancelled (returns [PatchbayResult.Ok]) or the stream fails. [onNal] is called on an IO thread
     * with each Annex-B NAL unit (SPS/PPS from the SDP first); it must not block for long.
     */
    public suspend fun stream(lease: StreamLease, onNal: (ByteArray) -> Unit): PatchbayResult<Unit> {
        val t = when (val r = parse(lease)) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
        val addr = when (val r = netGuard.address(t.host)) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
        val socket = when (val r = netGuard.connectTcp(addr, t.port, config.connectTimeoutMs)) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
        val hostPart = if (':' in addr.literal) "[${addr.literal}]" else addr.literal
        val base = "rtsp://$hostPart:${t.port}/${t.path}"
        return withContext(Dispatchers.IO) {
            socket.soTimeout = config.readTimeoutMs
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            try {
                socket.use { s ->
                    val conn = Conn(s.getInputStream().buffered(), s.getOutputStream(), t) { job?.isActive != false }
                    val describe = conn.exchange("DESCRIBE", base, listOf("Accept: application/sdp")).okOr { return@use it }
                    val sdp = SdpVideo.parse(describe.body.decodeToString()) ?: return@use PatchbayResult.Err(PatchbayError.Unsupported("the camera offers no H.264 video Patchbay can play"))
                    val track = if (sdp.control == "*") base else "$base/${sdp.control}"
                    val setup = conn.exchange("SETUP", track, listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1")).okOr { return@use it }
                    val session = setup.response.header("session").firstOrNull()?.substringBefore(';')?.trim()?.takeIf { it.length in 1..64 && it.none { c -> c.isISOControl() || c == ' ' } }
                    val sessionHeader = session?.let { "Session: $it" }
                    val channel = Regex("interleaved=(\\d+)-(\\d+)").find(setup.response.header("transport").firstOrNull().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    conn.exchange("PLAY", base, listOfNotNull(sessionHeader, "Range: npt=0.000-")).okOr { return@use it }
                    val ps = sdp.parameterSetsAnnexB()
                    if (ps.isNotEmpty()) onNal(ps)
                    val depack = H264Depacketizer()
                    val result = conn.readMedia(channel, depack, onNal, sessionHeader, base)
                    droppedPackets = depack.dropped
                    result
                }
            } catch (e: SocketTimeoutException) {
                PatchbayResult.Err(PatchbayError.Timeout("the camera stopped sending"))
            } catch (e: IOException) {
                PatchbayResult.Err(PatchbayError.Unreachable("camera connection failed: ${e.javaClass.simpleName}"))
            }
        }
    }

    private class Reply(val response: RtspProtocol.Response, val body: ByteArray)

    private inline fun PatchbayResult<Reply>.okOr(fail: (PatchbayResult.Err) -> Nothing): Reply = when (this) {
        is PatchbayResult.Ok -> value
        is PatchbayResult.Err -> fail(this)
    }

    private inner class Conn(val input: InputStream, val out: java.io.OutputStream, val t: Target, val stillWanted: () -> Boolean) {
        private var cseq = 1
        private var challenge: RtspProtocol.Challenge? = null
        private var lastData = clock()

        private fun build(method: String, uri: String, headers: List<String>, auth: String?): ByteArray {
            require(uri.none { it == '\r' || it == '\n' || it == ' ' })
            val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: Patchbay\r\n")
            for (h in headers) { require(h.none { it == '\r' || it == '\n' }); sb.append(h).append("\r\n") }
            if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n")
            return sb.append("\r\n").toString().encodeToByteArray()
        }

        private fun authFor(method: String, uri: String): String? {
            val c = challenge ?: return null
            val u = t.user ?: return null
            val p = t.password ?: return null
            return RtspAuth.authorization(c, u, p, method, uri, Random.nextBytes(8).joinToString("") { "%02x".format(it) })
        }

        fun exchange(method: String, uri: String, headers: List<String>): PatchbayResult<Reply> {
            var attempt = 0
            while (true) {
                out.write(build(method, uri, headers, authFor(method, uri))); out.flush()
                val reply = when (val r = readReply(true)) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
                when (reply.response.status) {
                    200 -> return PatchbayResult.Ok(reply)
                    401 -> {
                        if (t.user == null || attempt++ >= 1) return PatchbayResult.Err(PatchbayError.Unauthorized("the camera rejected the username or password"))
                        challenge = RtspProtocol.parseChallenge(reply.response.header("www-authenticate"))
                            ?: return PatchbayResult.Err(PatchbayError.Unsupported("the camera offered no login method Patchbay supports"))
                    }
                    404 -> return PatchbayResult.Err(PatchbayError.Unreachable("the camera has no stream at /${t.path}; is RTSP switched on for this camera?"))
                    else -> return PatchbayResult.Err(PatchbayError.Unreachable("the camera answered RTSP ${reply.response.status} to $method"))
                }
            }
        }

        /** Reads an RTSP response. Interleaved media that arrives first (after PLAY) is not expected here; it is a violation before PLAY completes. */
        private fun readReply(strict: Boolean): PatchbayResult<Reply> {
            val head = ByteArrayOutputStream()
            var tail = 0
            val deadline = clock() + 8_000
            while (true) {
                val b = try { input.read() } catch (e: SocketTimeoutException) { if (clock() > deadline) return PatchbayResult.Err(PatchbayError.Timeout("the camera didn't answer")); continue }
                if (b < 0) return PatchbayResult.Err(PatchbayError.Unreachable("the camera closed the connection"))
                head.write(b)
                tail = (tail shl 8) or b
                if (tail == 0x0D0A0D0A) break
                if (head.size() > RtspProtocol.MAX_HEADER_BYTES) return PatchbayResult.Err(PatchbayError.ProtocolViolation("rtsp: header block too large"))
            }
            val resp = when (val r = RtspProtocol.parseResponse(head.toByteArray())) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
            val len = resp.header("content-length").firstOrNull()?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) return PatchbayResult.Err(PatchbayError.ProtocolViolation("rtsp: body too large"))
            val body = ByteArray(len)
            readFully(body, deadline)
            lastData = clock()
            return PatchbayResult.Ok(Reply(resp, body))
        }

        private fun readFully(buf: ByteArray, deadline: Long) {
            var got = 0
            while (got < buf.size) {
                val r = try { input.read(buf, got, buf.size - got) } catch (e: SocketTimeoutException) { if (clock() > deadline) throw e; continue }
                if (r < 0) throw IOException("closed")
                got += r
            }
        }

        fun readMedia(channel: Int, depack: H264Depacketizer, onNal: (ByteArray) -> Unit, session: String?, base: String): PatchbayResult<Unit> {
            var lastKeepAlive = clock()
            val packet = ByteArray(65_535)
            while (true) {
                if (!stillWanted()) return PatchbayResult.Ok(Unit)
                val now = clock()
                if (now - lastData > config.stallMs) return PatchbayResult.Err(PatchbayError.Timeout("the camera stopped sending"))
                if (now - lastKeepAlive > config.keepAliveMs) {
                    out.write(build("OPTIONS", base, listOfNotNull(session), authFor("OPTIONS", base))); out.flush()
                    lastKeepAlive = now
                }
                val first = try { input.read() } catch (e: SocketTimeoutException) { continue }
                if (first < 0) return PatchbayResult.Err(PatchbayError.Unreachable("the camera closed the connection"))
                lastData = clock()
                when (first) {
                    '$'.code -> {
                        val hdr = ByteArray(3)
                        readFully(hdr, clock() + 5_000)
                        val ch = hdr[0].toInt() and 0xff
                        val n = ((hdr[1].toInt() and 0xff) shl 8) or (hdr[2].toInt() and 0xff)
                        readFully(packet, 0, n, clock() + 5_000)
                        if (ch == channel) for (nal in depack.push(packet, 0, n)) onNal(nal)
                    }
                    'R'.code -> { // a response to our keep-alive: "RTSP/1.0 ..." (the R is already consumed)
                        val rest = ByteArray(4)
                        readFully(rest, clock() + 5_000)
                        if (String(rest) != "TSP/") return PatchbayResult.Err(PatchbayError.ProtocolViolation("rtsp: unexpected bytes in the media stream"))
                        skipRestOfReply()
                    }
                    else -> return PatchbayResult.Err(PatchbayError.ProtocolViolation("rtsp: unexpected bytes in the media stream"))
                }
            }
        }

        private fun readFully(buf: ByteArray, off: Int, n: Int, deadline: Long) {
            var got = 0
            while (got < n) {
                val r = try { input.read(buf, off + got, n - got) } catch (e: SocketTimeoutException) { if (clock() > deadline) throw e; continue }
                if (r < 0) throw IOException("closed")
                got += r
            }
        }

        private fun skipRestOfReply() {
            var tail = 0x5254_5350 // "RTSP", so the header-end scan continues from a known state
            var count = 9
            val deadline = clock() + 5_000
            while (true) {
                val b = try { input.read() } catch (e: SocketTimeoutException) { if (clock() > deadline) throw e; continue }
                if (b < 0) throw IOException("closed")
                tail = (tail shl 8) or b
                if (++count > RtspProtocol.MAX_HEADER_BYTES) throw IOException("oversize reply")
                if (tail == 0x0D0A0D0A) return
            }
        }
    }

    private companion object {
        const val MAX_BODY = 16 * 1024
    }
}
