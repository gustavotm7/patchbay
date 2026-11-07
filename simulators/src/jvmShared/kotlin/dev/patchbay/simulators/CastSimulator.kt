package dev.patchbay.simulators

import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * A fake Chromecast: Cast v2 over TLS (self-signed, like the real device) with its own independent protobuf framing, so a
 * mistake in the adapter's codec isn't mirrored here. Answers connect and heartbeat, receiver status and volume, and the media
 * namespace of a running app. Binds loopback only. Fault injection: [offline], [badLength], [garbageFrames].
 */
public class CastSimulator(
    public val address: String,
    keyStore: KeyStore,
    keyPassword: CharArray,
    public val friendlyName: String = "Living room TV",
) : AutoCloseable {

    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
    }

    @Volatile public var volume: Double = 0.4
    @Volatile public var muted: Boolean = false
    @Volatile public var appRunning: Boolean = true
    @Volatile public var playerState: String = "PLAYING"
    @Volatile public var offline: Boolean = false
    @Volatile public var badLength: Boolean = false
    @Volatile public var garbageFrames: Boolean = false

    /** Every message received: namespace, destination and payload. */
    public val received: MutableList<Triple<String, String, String>> = CopyOnWriteArrayList()

    private val bind = InetAddress.getByName(address)
    private val server: SSLServerSocket
    public val port: Int get() = server.localPort
    private val clients = CopyOnWriteArrayList<OutputStream>()

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, keyPassword) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 8, bind) as SSLServerSocket
        thread(isDaemon = true, name = "cast-sim-accept") {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "cast-sim-conn") { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
    }

    private fun serve(c: Socket) {
        if (offline) return
        val inp = DataInputStream(c.getInputStream())
        val out = c.getOutputStream()
        clients += out
        try {
            if (badLength) { out.write(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f)); out.flush(); return }
            if (garbageFrames) { send(out, byteArrayOf(0x7f, 0x01, 0x02), raw = true); }
            while (true) {
                val len = inp.readInt()
                if (len !in 1..65_536) return
                val body = ByteArray(len).also { inp.readFully(it) }
                val m = decode(body) ?: continue
                received += Triple(m.ns, m.dest, m.payload)
                when {
                    m.ns.endsWith("heartbeat") && "PING" in m.payload -> reply(out, "receiver-0", m.ns, """{"type":"PONG"}""")
                    m.ns.endsWith("receiver") && "GET_STATUS" in m.payload -> reply(out, "receiver-0", m.ns, receiverStatus())
                    m.ns.endsWith("receiver") && "SET_VOLUME" in m.payload -> {
                        Regex(""""level":([0-9.]+)""").find(m.payload)?.let { volume = it.groupValues[1].toDouble() }
                        Regex(""""muted":(true|false)""").find(m.payload)?.let { muted = it.groupValues[1].toBoolean() }
                        broadcast("receiver-0", m.ns, receiverStatus())
                    }
                    m.ns.endsWith("media") && "GET_STATUS" in m.payload -> reply(out, "web-5", m.ns, mediaStatus())
                    m.ns.endsWith("media") && ("\"PLAY\"" in m.payload || "\"PAUSE\"" in m.payload || "\"STOP\"" in m.payload) -> {
                        playerState = when {
                            "\"PLAY\"" in m.payload -> "PLAYING"
                            "\"PAUSE\"" in m.payload -> "PAUSED"
                            else -> "IDLE"
                        }
                        if (playerState == "IDLE") appRunning = false
                        broadcast("web-5", m.ns, mediaStatus())
                        if (!appRunning) broadcast("receiver-0", "urn:x-cast:com.google.cast.receiver", receiverStatus())
                    }
                }
            }
        } finally {
            clients -= out
        }
    }

    /** Pushes the current receiver status to every client, like the device does when something changes. */
    public fun pushStatus() {
        broadcast("receiver-0", "urn:x-cast:com.google.cast.receiver", receiverStatus())
    }

    private fun receiverStatus(): String {
        val apps = if (appRunning) ""","applications":[{"displayName":"YouTube","appId":"233637DE","transportId":"web-5","sessionId":"s1"}]""" else ""
        return """{"type":"RECEIVER_STATUS","requestId":1,"status":{"volume":{"level":$volume,"muted":$muted}$apps}}"""
    }

    private fun mediaStatus(): String =
        if (!appRunning || playerState == "IDLE") """{"type":"MEDIA_STATUS","requestId":1,"status":[]}"""
        else """{"type":"MEDIA_STATUS","requestId":1,"status":[{"mediaSessionId":3,"playerState":"$playerState","currentTime":12.5,"media":{"duration":240.0,"metadata":{"title":"Song","artist":"Band"}}}]}"""

    private fun reply(out: OutputStream, src: String, ns: String, payload: String) = send(out, encode(src, "sender-patchbay", ns, payload))

    private fun broadcast(src: String, ns: String, payload: String) {
        val bytes = encode(src, "sender-patchbay", ns, payload)
        for (o in clients) runCatching { send(o, bytes) }
    }

    private fun send(out: OutputStream, body: ByteArray, raw: Boolean = false) = synchronized(out) {
        if (!raw) out.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())) else out.write(byteArrayOf(0, 0, 0, body.size.toByte()))
        out.write(body); out.flush()
    }

    // ---- independent protobuf (varint + length-delimited only) ----

    private class Msg(val dest: String, val ns: String, val payload: String)

    private fun encode(src: String, dest: String, ns: String, payload: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun v(x: Int) { var n = x; while (n >= 0x80) { o.write((n and 0x7f) or 0x80); n = n ushr 7 }; o.write(n) }
        fun s(f: Int, t: String) { val b = t.toByteArray(); o.write((f shl 3) or 2); v(b.size); o.write(b) }
        o.write(0x08); o.write(0); s(2, src); s(3, dest); s(4, ns); o.write(0x28); o.write(0); s(6, payload)
        return o.toByteArray()
    }

    private fun decode(b: ByteArray): Msg? {
        var i = 0
        fun v(): Int { var r = 0; var sh = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f) shl sh); if (x < 0x80) return r; sh += 7 } }
        var dest = ""; var ns = ""; var payload = ""
        return try {
            while (i < b.size) {
                val tag = v()
                if (tag and 7 == 0) { v(); continue }
                val len = v(); val t = String(b, i, len); i += len
                when (tag ushr 3) { 3 -> dest = t; 4 -> ns = t; 6 -> payload = t }
            }
            Msg(dest, ns, payload)
        } catch (e: Exception) { null }
    }

    override fun close() { server.close() }
}
