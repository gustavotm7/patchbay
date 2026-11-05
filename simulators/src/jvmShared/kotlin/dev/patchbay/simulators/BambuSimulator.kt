package dev.patchbay.simulators

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * A fake Bambu H2D on loopback: an MQTT 3.1.1 broker subset over TLS, checking `bblp` + access code, plus an
 * SSDP responder. On `pushall` it publishes a full H2D-shaped report; commands update state and publish an
 * incremental report, like the firmware does.
 */
public class BambuSimulator(
    public val address: String,
    public val serial: String,
    public val accessCode: String,
    keyStore: KeyStore,
    keyPassword: CharArray,
) : AutoCloseable {
    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
    }

    @Volatile public var gcodeState: String = "RUNNING"
    @Volatile public var percent: Int = 47
    @Volatile public var layer: Int = 112
    @Volatile public var lightOn: Boolean = true
    public val commands: MutableList<String> = CopyOnWriteArrayList()

    private val bind = InetAddress.getByName(address)
    private val server: SSLServerSocket
    private val ssdp = DatagramSocket(InetSocketAddress(bind, 0))
    private val clients = CopyOnWriteArrayList<OutputStream>()
    public val mqttPort: Int get() = server.localPort
    public val ssdpPort: Int get() = ssdp.localPort

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, keyPassword) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 8, bind) as SSLServerSocket
        thread(isDaemon = true, name = "bambu-sim-accept") {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "bambu-sim-conn") { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
        thread(isDaemon = true, name = "bambu-sim-ssdp") {
            val buf = ByteArray(2048)
            while (!ssdp.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { ssdp.receive(p) }.isFailure) break
                val reply = ("HTTP/1.1 200 OK\r\nLocation: $address\r\nST: urn:bambulab-com:device:3dprinter:1\r\nUSN: $serial\r\n" +
                    "DevModel.bambu.com: H2D\r\nDevName.bambu.com: Workshop H2D\r\nDevConnect.bambu.com: lan\r\n\r\n").toByteArray()
                ssdp.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
    }

    /** Pushes a progress update to connected clients, like the printer does every few seconds. */
    public fun tick(newPercent: Int, newLayer: Int) {
        percent = newPercent; layer = newLayer
        broadcast("""{"print":{"mc_percent":$percent,"layer_num":$layer}}""")
    }

    private fun full(): String =
        """{"print":{"command":"push_status","gcode_state":"$gcodeState","mc_percent":$percent,"layer_num":$layer,"total_layer_num":240,
        "mc_remaining_time":96,"subtask_name":"enclosure_bracket_v3","hms":[],
        "lights_report":[{"node":"chamber_light","mode":"${if (lightOn) "on" else "off"}"},{"node":"chamber_light2","mode":"${if (lightOn) "on" else "off"}"}],
        "device":{"extruder":{"info":[{"id":0,"temp":14418139},{"id":1,"temp":38}],"state":2},"bed":{"info":{"temp":4259905}},"ctc":{"info":{"temp":34}}},
        "ams":{"tray_now":"1","ams":[{"id":"0","tray":[{"id":"0","tray_type":"PLA","tray_color":"F2F2EEFF","remain":80},
        {"id":"1","tray_type":"PETG","tray_color":"1F1F22FF","remain":55},{"id":"2","tray_type":"PLA","tray_color":"E04B2AFF","remain":12},
        {"id":"3","tray_type":"","tray_color":"00000000","remain":-1}]}]}}}"""

    private fun serve(c: Socket) {
        val inp = c.getInputStream()
        val out = c.getOutputStream()
        // CONNECT
        val (t0, connect) = readPacket(inp) ?: return
        if (t0 shr 4 != 1) return
        val (user, pass) = parseCredentials(connect)
        val ok = user == "bblp" && pass == accessCode
        write(out, 0x20, byteArrayOf(0, if (ok) 0 else 5))
        if (!ok) return
        clients += out
        try {
            while (true) {
                val (type, body) = readPacket(inp) ?: return
                when (type shr 4) {
                    8 -> write(out, 0x90, byteArrayOf(body[0], body[1], 0)) // SUBACK
                    12 -> write(out, 0xD0, ByteArray(0)) // PINGRESP
                    14 -> return // DISCONNECT
                    3 -> {
                        val tlen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                        val topic = String(body, 2, tlen)
                        if (topic != "device/$serial/request") continue
                        val msg = runCatching { Json.parseToJsonElement(String(body, 2 + tlen, body.size - 2 - tlen)).jsonObject }.getOrNull() ?: continue
                        handle(msg)?.let { publish(out, it) }
                    }
                }
            }
        } finally {
            clients -= out
        }
    }

    private fun handle(msg: JsonObject): String? {
        (msg["pushing"] as? JsonObject)?.let { if ((it["command"] as? JsonPrimitive)?.content == "pushall") return full() }
        (msg["print"] as? JsonObject)?.let { p ->
            val cmd = (p["command"] as? JsonPrimitive)?.content ?: return null
            commands += cmd
            gcodeState = when (cmd) { "pause" -> "PAUSE"; "resume" -> "RUNNING"; "stop" -> "FAILED"; else -> gcodeState }
            return """{"print":{"gcode_state":"$gcodeState"}}"""
        }
        (msg["system"] as? JsonObject)?.let { s ->
            if ((s["command"] as? JsonPrimitive)?.content != "ledctrl") return null
            val node = (s["led_node"] as? JsonPrimitive)?.content
            commands += "ledctrl:$node"
            if (node == "chamber_light") lightOn = (s["led_mode"] as? JsonPrimitive)?.content == "on"
            return """{"print":{"lights_report":[{"node":"chamber_light","mode":"${if (lightOn) "on" else "off"}"},{"node":"chamber_light2","mode":"${if (lightOn) "on" else "off"}"}]}}"""
        }
        return null
    }

    private fun broadcast(payload: String) = clients.forEach { runCatching { publish(it, payload) } }

    private fun publish(out: OutputStream, payload: String) {
        val topic = "device/$serial/report".toByteArray()
        val b = ByteArrayOutputStream()
        b.write(topic.size shr 8); b.write(topic.size and 0xFF); b.write(topic); b.write(payload.toByteArray())
        write(out, 0x30, b.toByteArray())
    }

    private fun write(out: OutputStream, header: Int, body: ByteArray) {
        synchronized(out) {
            val b = ByteArrayOutputStream()
            b.write(header)
            var x = body.size
            do { var d = x % 128; x /= 128; if (x > 0) d = d or 0x80; b.write(d) } while (x > 0)
            b.write(body)
            out.write(b.toByteArray()); out.flush()
        }
    }

    private fun parseCredentials(b: ByteArray): Pair<String?, String?> {
        var i = 0
        fun str(): String { val l = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); val s = String(b, i + 2, l); i += 2 + l; return s }
        str() // "MQTT"
        i += 1 // level
        val flags = b[i].toInt() and 0xFF; i += 1
        i += 2 // keepalive
        str() // client id
        val user = if (flags and 0x80 != 0) str() else null
        val pass = if (flags and 0x40 != 0) str() else null
        return user to pass
    }

    private fun readPacket(inp: InputStream): Pair<Int, ByteArray>? {
        val type = inp.read(); if (type < 0) return null
        var mult = 1; var len = 0
        while (true) { val d = inp.read(); if (d < 0) throw EOFException(); len += (d and 0x7F) * mult; if (d and 0x80 == 0) break; mult *= 128 }
        val body = ByteArray(len); var off = 0
        while (off < len) { val r = inp.read(body, off, len - off); if (r < 0) throw EOFException(); off += r }
        return type to body
    }

    override fun close() {
        server.close(); ssdp.close()
    }
}
