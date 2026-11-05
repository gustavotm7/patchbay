package dev.patchbay.simulators

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * A fake LG webOS TV: SSAP over WebSocket over TLS (self-signed, like real TVs), plus an SSDP responder.
 * Loopback only. Pairing behaves like the firmware: an unknown client gets a PROMPT, and [userAccepts]
 * decides what "the user on the sofa" does.
 */
public class WebOsSimulator(
    public val address: String,
    keyStore: KeyStore,
    keyPassword: CharArray,
) : AutoCloseable {
    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
    }

    @Volatile public var userAccepts: Boolean = true
    @Volatile public var on: Boolean = true
    @Volatile public var volume: Int = 14
    @Volatile public var muted: Boolean = false
    @Volatile public var input: String = "HDMI_2"
    public val inputs: List<Pair<String, String>> = listOf("HDMI_1" to "HDMI 1", "HDMI_2" to "HDMI 2", "HDMI_3" to "PS5")

    /** Keys this TV has issued (a real TV remembers paired apps). */
    public val issuedKeys: MutableList<String> = CopyOnWriteArrayList()
    public val requests: MutableList<String> = CopyOnWriteArrayList()

    private val bind = InetAddress.getByName(address)
    private val server: SSLServerSocket
    private val ssdp = DatagramSocket(InetSocketAddress(bind, 0))
    private val wol = DatagramSocket(InetSocketAddress(bind, 0))
    public val wssPort: Int get() = server.localPort
    public val ssdpPort: Int get() = ssdp.localPort
    public val wolPort: Int get() = wol.localPort
    public val mac: String = "a8:23:fe:12:34:56"

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, keyPassword) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = ctx.serverSocketFactory.createServerSocket(0, 8, bind) as SSLServerSocket
        thread(isDaemon = true, name = "webos-sim-accept") {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "webos-sim-conn") { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
        thread(isDaemon = true, name = "webos-sim-wol") {
            val buf = ByteArray(256)
            val macBytes = mac.split(':').map { it.toInt(16).toByte() }.toByteArray()
            while (!wol.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { wol.receive(p) }.isFailure) break
                val d = p.data.copyOf(p.length)
                val valid = d.size == 102 && (0 until 6).all { d[it] == 0xFF.toByte() } &&
                    (0 until 16).all { i -> d.copyOfRange(6 + i * 6, 12 + i * 6).contentEquals(macBytes) }
                if (valid) on = true
            }
        }
        thread(isDaemon = true, name = "webos-sim-ssdp") {
            val buf = ByteArray(2048)
            while (!ssdp.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { ssdp.receive(p) }.isFailure) break
                val reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://$address:1990/\r\n" +
                    "SERVER: WebOS/6.0 UPnP/1.0\r\nST: urn:lge-com:service:webos-second-screen:1\r\n" +
                    "USN: uuid:4a3b-sim-tv::urn:lge-com:service:webos-second-screen:1\r\n\r\n").toByteArray()
                ssdp.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }
    }

    private fun serve(c: Socket) {
        val inp = c.getInputStream()
        val out = c.getOutputStream()
        val head = readHead(inp) ?: return
        val key = head.lines().firstOrNull { it.lowercase().startsWith("sec-websocket-key:") }?.substringAfter(':')?.trim() ?: return
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
        out.flush()
        var registered = false
        while (true) {
            val text = readClientText(inp) ?: return
            val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val type = (msg["type"] as? JsonPrimitive)?.content
            val id = (msg["id"] as? JsonPrimitive)?.content ?: ""
            val payload = msg["payload"] as? JsonObject ?: JsonObject(emptyMap())
            when (type) {
                "hello" -> send(out, """{"type":"hello","id":"$id","payload":{"protocolVersion":1,"deviceType":"tv","deviceOS":"webOS"}}""")
                "register" -> {
                    val presented = (payload["client-key"] as? JsonPrimitive)?.content
                    if (presented != null && presented in issuedKeys) {
                        registered = true
                        send(out, """{"type":"registered","id":"$id","payload":{"client-key":"$presented"}}""")
                    } else {
                        send(out, """{"type":"response","id":"$id","payload":{"pairingType":"PROMPT","returnValue":true}}""")
                        Thread.sleep(50) // the user picks up the remote
                        if (userAccepts) {
                            val newKey = (1..32).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
                            issuedKeys += newKey
                            registered = true
                            send(out, """{"type":"registered","id":"$id","payload":{"client-key":"$newKey"}}""")
                        } else {
                            send(out, """{"type":"error","id":"$id","error":"403 User denied access","payload":{}}""")
                        }
                    }
                }
                "request" -> {
                    val uri = (msg["uri"] as? JsonPrimitive)?.content?.removePrefix("ssap://") ?: ""
                    requests += uri
                    if (!registered && uri != "system/getSystemInfo") {
                        send(out, """{"type":"error","id":"$id","error":"401 insufficient permissions","payload":{}}""")
                        continue
                    }
                    val body = when (uri) {
                        "system/getSystemInfo" -> """{"returnValue":true,"modelName":"OLED55C3PUA","features":{}}"""
                        "audio/getVolume" -> """{"returnValue":true,"volumeStatus":{"volume":$volume,"muteStatus":$muted}}"""
                        "audio/setVolume" -> { (payload["volume"] as? JsonPrimitive)?.intOrNull?.let { volume = it }; """{"returnValue":true}""" }
                        "audio/setMute" -> { (payload["mute"] as? JsonPrimitive)?.booleanOrNull?.let { muted = it }; """{"returnValue":true}""" }
                        "com.webos.service.tvpower/power/getPowerState" -> """{"returnValue":true,"state":"${if (on) "Active" else "Active Standby"}"}"""
                        "system/turnOff" -> { on = false; """{"returnValue":true}""" }
                        "com.webos.service.connectionmanager/getinfo" -> """{"returnValue":true,"wiredInfo":{"macAddress":"$mac"},"wifiInfo":{"macAddress":"a8:23:fe:00:00:01"}}"""
                        "tv/getExternalInputList" -> """{"returnValue":true,"devices":[${inputs.joinToString(",") { (i, l) -> """{"id":"$i","label":"$l"}""" }}]}"""
                        "tv/switchInput" -> { (payload["inputId"] as? JsonPrimitive)?.content?.let { input = it }; """{"returnValue":true}""" }
                        "media.controls/play", "media.controls/pause", "media.controls/stop" -> """{"returnValue":true}"""
                        else -> null
                    }
                    if (body == null) send(out, """{"type":"error","id":"$id","error":"404 no such service or method","payload":{}}""")
                    else send(out, """{"type":"response","id":"$id","payload":$body}""")
                }
            }
        }
    }

    private fun send(out: OutputStream, text: String) {
        val b = text.toByteArray()
        synchronized(out) {
            out.write(0x81)
            when {
                b.size < 126 -> out.write(b.size)
                else -> { out.write(126); out.write(b.size shr 8); out.write(b.size and 0xFF) }
            }
            out.write(b)
            out.flush()
        }
    }

    private fun readClientText(inp: InputStream): String? {
        val b0 = inp.read(); if (b0 < 0) return null
        val b1 = inp.read(); if (b1 < 0) return null
        if (b1 and 0x80 == 0) return null // clients must mask
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((inp.read() shl 8) or inp.read()).toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or inp.read().toLong() } }
        val mask = ByteArray(4) { inp.read().toByte() }
        val data = ByteArray(len.toInt())
        var off = 0
        while (off < data.size) { val r = inp.read(data, off, data.size - off); if (r < 0) return null; off += r }
        for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return when (b0 and 0x0F) {
            0x1 -> String(data, Charsets.UTF_8)
            0x8 -> null
            else -> ""
        }
    }

    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last = 0
        while (buf.size() < 8192) {
            val b = inp.read(); if (b < 0) return null
            buf.write(b); last = (last shl 8) or b
            if (last == 0x0D0A0D0A) return buf.toString(Charsets.ISO_8859_1)
        }
        return null
    }

    override fun close() {
        server.close()
        ssdp.close()
        wol.close()
    }
}
