package dev.patchbay.simulators

import dev.patchbay.core.adapter.kasa.KasaProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A fake Kasa plug on the legacy protocol: answers the scrambled get_sysinfo query on UDP and TCP (same
 * port, as real plugs do) and takes set_relay_state on TCP. Binds loopback only; each fake plug takes its
 * own 127.0.0.x address. Fault injection: [offline], [malformedReplies], [oversizeFrame], [errCode].
 */
public class KasaSimulator(
    public val address: String,
    public val deviceId: String,
    public val model: String = "HS103(US)",
    public var alias: String = "Desk lamp",
    port: Int = 0,
    /** Real "type" field; set to something else to simulate a bulb or strip answering the scan. */
    public val type: String = "IOT.SMARTPLUGSWITCH",
    public val withChildren: Boolean = false,
) : AutoCloseable {

    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
    }

    @Volatile public var on: Boolean = false
    @Volatile public var offline: Boolean = false
    @Volatile public var malformedReplies: Boolean = false
    @Volatile public var oversizeFrame: Boolean = false
    @Volatile public var errCode: Int = 0

    /** Ignores this many incoming UDP datagrams (a plug that missed a broadcast). */
    @Volatile public var dropNextUdp: Int = 0

    /** Every plaintext request received (UDP and TCP). */
    public val received: MutableList<String> = CopyOnWriteArrayList()

    private val bind = InetAddress.getByName(address)
    private val tcp = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, port)) }
    public val port: Int get() = tcp.localPort
    private val udp = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(bind, tcp.localPort)) }

    init {
        thread(isDaemon = true, name = "kasa-sim-udp-$address") { udpLoop() }
        thread(isDaemon = true, name = "kasa-sim-tcp-$address") { tcpLoop() }
    }

    private fun sysinfo(): String {
        val children = if (withChildren) ""","children":[{"id":"00","state":1}]""" else ""
        return """{"system":{"get_sysinfo":{"err_code":$errCode,"sw_ver":"1.1.0","model":"$model","type":"$type",""" +
            """"alias":"$alias","relay_state":${if (on) 1 else 0},"deviceId":"$deviceId"$children}}}"""
    }

    /** Returns the plaintext reply, or null for no reply. */
    private fun handle(request: String): String? {
        received += request
        if (offline) return null
        return when {
            "get_sysinfo" in request -> sysinfo()
            "set_relay_state" in request -> {
                if (errCode == 0) on = """"state":1""" in request
                """{"system":{"set_relay_state":{"err_code":$errCode}}}"""
            }
            else -> null
        }
    }

    private fun garble(plain: String): ByteArray {
        val wire = KasaProtocol.scramble(plain.toByteArray())
        return if (malformedReplies) wire.copyOf(wire.size / 2).also { if (it.isNotEmpty()) it[0] = (it[0].toInt() xor 0x55).toByte() } else wire
    }

    private fun udpLoop() {
        val buf = ByteArray(4096)
        while (!udp.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { udp.receive(p) } catch (e: SocketException) { return }
            if (dropNextUdp > 0) { dropNextUdp--; continue }
            val reply = handle(KasaProtocol.unscramble(p.data.copyOf(p.length)).decodeToString()) ?: continue
            val out = garble(reply)
            udp.send(DatagramPacket(out, out.size, p.socketAddress))
        }
    }

    private fun tcpLoop() {
        while (!tcp.isClosed) {
            val client = try { tcp.accept() } catch (e: SocketException) { return }
            thread(isDaemon = true) {
                client.use { c ->
                    c.soTimeout = 2_000
                    try {
                        val inp = c.getInputStream()
                        val header = ByteArray(4).also { readFully(inp, it) }
                        val len = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                        if (len !in 1..8192) return@thread
                        val body = ByteArray(len).also { readFully(inp, it) }
                        val reply = handle(KasaProtocol.unscramble(body).decodeToString()) ?: return@thread
                        val out = if (oversizeFrame) {
                            byteArrayOf(0, 0, 0x7f, 0)
                        } else {
                            val w = garble(reply)
                            val n = w.size
                            byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + w
                        }
                        c.getOutputStream().apply { write(out); flush() }
                    } catch (e: java.io.IOException) {
                        // peer went away: nothing to do
                    }
                }
            }
        }
    }

    private fun readFully(inp: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = inp.read(b, off, b.size - off)
            if (r < 0) throw java.io.IOException("eof")
            off += r
        }
    }

    override fun close() {
        tcp.close()
        udp.close()
    }
}
