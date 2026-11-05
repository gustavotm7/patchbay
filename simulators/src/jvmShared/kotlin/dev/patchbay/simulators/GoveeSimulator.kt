package dev.patchbay.simulators

import dev.patchbay.core.model.Rgb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A fake Govee LAN light. It behaves like the firmware does: it answers `scan` on [scanPort], takes
 * commands and `devStatus` on [commandPort], and replies to the sender's IP at [replyPort] (real devices
 * always reply to 4002, whatever port the request came from).
 *
 * Binds **loopback only**: each fake device takes its own 127.0.0.x address, so source-address matching
 * in the adapter is exercised. Fault injection: [offline], [malformedReplies].
 */
public class GoveeSimulator(
    public val address: String,
    public val deviceId: String,
    public val sku: String,
    /** Where replies go (the adapter's listen port). Settable because tests learn it after binding. */
    @Volatile public var replyPort: Int,
    scanPort: Int = 0,
    commandPort: Int = 0,
) : AutoCloseable {

    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
    }

    @Volatile public var on: Boolean = true
    @Volatile public var brightness: Int = 62
    @Volatile public var rgb: Rgb = Rgb(255, 180, 107)
    @Volatile public var kelvin: Int = 2700
    @Volatile public var offline: Boolean = false
    @Volatile public var malformedReplies: Boolean = false

    /** Every command received, with a monotonic timestamp (for pacing tests). */
    public val received: MutableList<Pair<Long, String>> = CopyOnWriteArrayList()

    private val bind = InetAddress.getByName(address)
    private val scanSocket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(bind, scanPort)) }
    private val commandSocket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(bind, commandPort)) }
    public val scanPort: Int get() = scanSocket.localPort
    public val commandPort: Int get() = commandSocket.localPort

    private val json = Json

    init {
        listOf(scanSocket, commandSocket).forEach { s -> thread(isDaemon = true, name = "govee-sim-$address-${s.localPort}") { loop(s) } }
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try {
                s.receive(p)
            } catch (e: SocketException) {
                return
            }
            val text = String(p.data, 0, p.length, Charsets.UTF_8)
            received += System.nanoTime() to text
            if (offline) continue
            val reply = handle(text) ?: continue
            val out = (if (malformedReplies) reply.dropLast(5) + "\u0000]]]" else reply).toByteArray()
            s.send(DatagramPacket(out, out.size, InetSocketAddress(p.address, replyPort)))
        }
    }

    private fun handle(text: String): String? {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject["msg"]!!.jsonObject }.getOrNull() ?: return null
        val cmd = (msg["cmd"] as? JsonPrimitive)?.content ?: return null
        val data = msg["data"] as? JsonObject ?: JsonObject(emptyMap())
        return when (cmd) {
            "scan" -> """{"msg":{"cmd":"scan","data":{"ip":"$address","device":"$deviceId","sku":"$sku",""" +
                """"bleVersionHard":"3.01.01","bleVersionSoft":"1.03.01","wifiVersionHard":"1.00.10","wifiVersionSoft":"1.02.03"}}}"""
            "devStatus" -> status()
            "turn" -> { on = data["value"]?.jsonPrimitive?.int == 1; null }
            "brightness" -> { data["value"]?.jsonPrimitive?.int?.let { if (it in 1..100) brightness = it }; null }
            "colorwc" -> {
                val k = data["colorTemInKelvin"]?.jsonPrimitive?.int ?: 0
                if (k in 2000..9000) {
                    kelvin = k
                } else {
                    val c = data["color"]?.jsonObject
                    if (c != null) {
                        rgb = Rgb(c["r"]!!.jsonPrimitive.int, c["g"]!!.jsonPrimitive.int, c["b"]!!.jsonPrimitive.int)
                        kelvin = 0
                    }
                }
                null
            }
            else -> null
        }
    }

    public fun status(): String =
        """{"msg":{"cmd":"devStatus","data":{"onOff":${if (on) 1 else 0},"brightness":$brightness,""" +
            """"color":{"r":${rgb.r},"g":${rgb.g},"b":${rgb.b}},"colorTemInKelvin":$kelvin}}}"""

    override fun close() {
        scanSocket.close()
        commandSocket.close()
    }
}
