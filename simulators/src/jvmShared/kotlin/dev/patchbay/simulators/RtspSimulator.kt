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
            if (offline) return@use
            val text = readHeaders(it.getInputStream()) ?: return@use
            requests += text
            val out = it.getOutputStream()
            if (garbage) { out.write("HELLO NOT RTSP\r\n\r\n".toByteArray()); return@use }
            if (hugeHeaders) {
                out.write("RTSP/1.0 200 OK\r\n".toByteArray())
                repeat(2_000) { i -> out.write("X-Pad-$i: ${"a".repeat(20)}\r\n".toByteArray()) }
                return@use
            }
            val cseq = Regex("CSeq: (\\d+)").find(text)?.groupValues?.get(1) ?: "0"
            val uri = Regex("^DESCRIBE (\\S+) RTSP/1.0").find(text)?.groupValues?.get(1)
            if (uri == null) { out.write(reply(400, cseq)); return@use }
            val authed = when (mode) {
                Mode.NONE -> true
                Mode.BASIC -> Regex("Authorization: Basic (\\S+)").find(text)?.groupValues?.get(1) ==
                    Base64.getEncoder().encodeToString("$user:$password".toByteArray())
                Mode.DIGEST -> digestOk(text, uri)
            }
            if (!authed) {
                val challenge = if (mode == Mode.BASIC) "Basic realm=\"$realm\"" else "Digest realm=\"$realm\", nonce=\"$nonce\", qop=\"auth\""
                out.write(reply(401, cseq, "WWW-Authenticate: $challenge"))
            } else if (!uri.endsWith("/$path")) {
                out.write(reply(404, cseq))
            } else {
                val sdp = "v=0\r\ns=sim\r\n"
                out.write(reply(200, cseq, "Content-Type: application/sdp", "Content-Length: ${sdp.length}") + sdp.toByteArray())
            }
            out.flush()
        } catch (e: java.io.IOException) {
            // client went away
        }
    }

    private fun digestOk(text: String, uri: String): Boolean {
        val h = Regex("Authorization: Digest (.+)").find(text)?.groupValues?.get(1) ?: return false
        fun p(k: String) = Regex("""$k="([^"]*)"""").find(h)?.groupValues?.get(1)
        val cnonce = p("cnonce") ?: return false
        if (p("username") != user || p("nonce") != nonce || p("uri") != uri) return false
        val nc = Regex("nc=(\\w+)").find(h)?.groupValues?.get(1) ?: return false
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("DESCRIBE:$uri")
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
