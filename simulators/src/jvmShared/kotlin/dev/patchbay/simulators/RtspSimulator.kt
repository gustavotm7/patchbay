package dev.patchbay.simulators

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A fake RTSP camera: answers DESCRIBE for [path], demanding Digest or Basic authentication (or none), and
 * checks the client's response with its own independent maths. Binds loopback only.
 * Fault injection: [offline] (accepts then hangs up), [garbage] (non-RTSP bytes), [hugeHeaders].
 */
public class RtspSimulator(
    public val address: String,
    private val user: String,
    private val password: String,
    public val path: String = "live0",
    public var mode: Mode = Mode.DIGEST,
    port: Int = 0,
) : AutoCloseable {

    public enum class Mode { NONE, BASIC, DIGEST }

    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
    }

    @Volatile public var offline: Boolean = false
    @Volatile public var garbage: Boolean = false
    @Volatile public var hugeHeaders: Boolean = false

    /** RTP packets sent (interleaved on channel 0) right after PLAY. */
    @Volatile public var rtpPackets: List<ByteArray> = emptyList()

    /** Raw bytes written after the RTP packets (to check that a stream with junk in it is refused). */
    @Volatile public var rawAfterPlay: ByteArray? = null

    /** Request methods received, in order. */
    public val methods: MutableList<String> = CopyOnWriteArrayList()

    /** Raw request texts received, to check that credentials only ever follow a challenge. */
    public val requests: MutableList<String> = CopyOnWriteArrayList()

    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName(address), port)) }
    public val port: Int get() = server.localPort
    private val realm = "eufy-sim"
    private val nonce = "5d41402abc4b2a76b9719d911017c592"

    init {
        thread(isDaemon = true, name = "rtsp-sim-$address") {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (e: SocketException) { return@thread }
                thread(isDaemon = true) { serve(c) }
            }
        }
    }

    private fun serve(c: java.net.Socket) = c.use {
        it.soTimeout = 2_000
        try {
            val inp = it.getInputStream()
            val out = it.getOutputStream()
            while (true) {
                val text = readHeaders(inp) ?: return@use
                requests += text
                if (offline) return@use
                if (garbage) { out.write("HELLO NOT RTSP\r\n\r\n".toByteArray()); return@use }
                if (hugeHeaders) {
                    out.write("RTSP/1.0 200 OK\r\n".toByteArray())
                    repeat(2_000) { i -> out.write("X-Pad-$i: ${"a".repeat(20)}\r\n".toByteArray()) }
                    return@use
                }
                val cseq = Regex("CSeq: (\\d+)").find(text)?.groupValues?.get(1) ?: "0"
                val m = Regex("^(\\w+) (\\S+) RTSP/1.0").find(text)
                if (m == null) { out.write(reply(400, cseq)); return@use }
                val method = m.groupValues[1]; val uri = m.groupValues[2]
                methods += method
                val authed = when (mode) {
                    Mode.NONE -> true
                    Mode.BASIC -> Regex("Authorization: Basic (\\S+)").find(text)?.groupValues?.get(1) ==
                        Base64.getEncoder().encodeToString("$user:$password".toByteArray())
                    Mode.DIGEST -> digestOk(text, method, uri)
                }
                if (!authed) {
                    val challenge = if (mode == Mode.BASIC) "Basic realm=\"$realm\"" else "Digest realm=\"$realm\", nonce=\"$nonce\", qop=\"auth\""
                    out.write(reply(401, cseq, "WWW-Authenticate: $challenge"))
                } else if (!uri.contains("/$path")) {
                    out.write(reply(404, cseq))
                } else when (method) {
                    "DESCRIBE" -> {
                        val sdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=sim\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
                            "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z2QAKKw=,aO48gA==\r\na=control:track1\r\n"
                        out.write(reply(200, cseq, "Content-Type: application/sdp", "Content-Length: ${sdp.length}") + sdp.toByteArray())
                    }
                    "SETUP" -> out.write(reply(200, cseq, "Session: 12345678;timeout=60", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1"))
                    "PLAY" -> {
                        out.write(reply(200, cseq, "Session: 12345678", "RTP-Info: url=$uri/track1;seq=1"))
                        for (p in rtpPackets) out.write(byteArrayOf('$'.code.toByte(), 0, (p.size shr 8).toByte(), p.size.toByte()) + p)
                        rawAfterPlay?.let { raw -> out.write(raw) }
                    }
                    else -> out.write(reply(200, cseq)) // OPTIONS / GET_PARAMETER keep-alives and TEARDOWN
                }
                out.flush()
                if (method == "TEARDOWN") return@use
            }
        } catch (e: java.io.IOException) {
            // client went away or went quiet
        }
    }

    private fun digestOk(text: String, method: String, uri: String): Boolean {
        val h = Regex("Authorization: Digest (.+)").find(text)?.groupValues?.get(1) ?: return false
        fun p(k: String) = Regex("""$k="([^"]*)"""").find(h)?.groupValues?.get(1)
        val cnonce = p("cnonce") ?: return false
        if (p("username") != user || p("nonce") != nonce || p("uri") != uri) return false
        val nc = Regex("nc=(\\w+)").find(h)?.groupValues?.get(1) ?: return false
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")
        return p("response") == md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun reply(status: Int, cseq: String, vararg headers: String): ByteArray {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found")[status] ?: "Status"
        return ("RTSP/1.0 $status $reason\r\nCSeq: $cseq\r\n" + headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray()
    }

    private fun readHeaders(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = inp.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    override fun close() { server.close() }
}
