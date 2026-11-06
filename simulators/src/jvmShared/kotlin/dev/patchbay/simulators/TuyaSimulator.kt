package dev.patchbay.simulators

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * A fake Tuya bulb speaking local protocol 3.3 (its own independent framing, CRC and AES code, so a mistake in
 * the adapter's codec isn't mirrored here). Defaults to the standard "v2" layout (data points 20 to 24).
 * Binds loopback only. Fault injection: [offline], [badChecksum], [oversizeLength], [garbage], [versionHeaderInQuery].
 */
public class TuyaSimulator(
    public val address: String,
    public val deviceId: String,
    private val localKey: String,
    port: Int = 0,
) : AutoCloseable {

    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
        require(localKey.length == 16) { "local key is 16 characters" }
    }

    public val dps: MutableMap<String, JsonPrimitive> = ConcurrentHashMap<String, JsonPrimitive>().apply {
        put("20", JsonPrimitive(true)); put("21", JsonPrimitive("white")); put("22", JsonPrimitive(500))
        put("23", JsonPrimitive(500)); put("24", JsonPrimitive("000003e803e8"))
    }

    @Volatile public var offline: Boolean = false
    @Volatile public var badChecksum: Boolean = false
    @Volatile public var oversizeLength: Boolean = false
    @Volatile public var garbage: Boolean = false
    @Volatile public var versionHeaderInQuery: Boolean = false

    /** The data-point maps received in control frames, in order. */
    public val writes: MutableList<Map<String, JsonPrimitive>> = CopyOnWriteArrayList()

    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName(address), port)) }
    public val port: Int get() = server.localPort
    private val key = SecretKeySpec(localKey.toByteArray(Charsets.US_ASCII), "AES")

    init {
        thread(isDaemon = true, name = "tuya-sim-$address") {
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
            val inp = it.getInputStream()
            val head = ByteArray(16).also { b -> readFully(inp, b) }
            val bb = ByteBuffer.wrap(head)
            check(bb.getInt() == 0x000055AA)
            val seq = bb.getInt(); val cmd = bb.getInt(); val len = bb.getInt()
            val rest = ByteArray(len).also { b -> readFully(inp, b) }
            val payload = rest.copyOf(len - 8)
            val out = it.getOutputStream()
            if (garbage) { out.write(ByteArray(40) { i -> (i * 7).toByte() }); return@use }
            val reply: ByteArray = when (cmd) {
                0x0A -> {
                    val ok = decrypt(payload)?.let { p -> Json.parseToJsonElement(p.decodeToString()).jsonObject["devId"]?.let { d -> (d as JsonPrimitive).content == deviceId } } == true
                    if (!ok) frame(seq, cmd, 1, "data format error".toByteArray())
                    else {
                        val json = JsonObject(dps.toMap()).let { d -> """{"devId":"$deviceId","dps":$d}""" }
                        val enc = encrypt(json.toByteArray())
                        frame(seq, cmd, 0, if (versionHeaderInQuery) "3.3".toByteArray() + ByteArray(12) + enc else enc)
                    }
                }
                0x07 -> {
                    val body = if (payload.size > 15 && payload.copyOf(3).contentEquals("3.3".toByteArray())) payload.copyOfRange(15, payload.size) else null
                    val plain = body?.let { b -> decrypt(b) }
                    val obj = plain?.let { p -> Json.parseToJsonElement(p.decodeToString()).jsonObject }
                    if (obj == null || (obj["devId"] as? JsonPrimitive)?.content != deviceId) frame(seq, cmd, 1, "data format error".toByteArray())
                    else {
                        val w = (obj["dps"] as JsonObject).mapValues { e -> e.value as JsonPrimitive }
                        writes += w
                        dps.putAll(w)
                        frame(seq, cmd, 0, ByteArray(0))
                    }
                }
                else -> frame(seq, cmd, 1, ByteArray(0))
            }
            out.write(reply); out.flush()
        } catch (e: Exception) {
            // malformed or closed: hang up
        }
    }

    private fun frame(seq: Int, cmd: Int, retcode: Int, payload: ByteArray): ByteArray {
        val body = ByteBuffer.allocate(4 + payload.size).putInt(retcode).put(payload).array()
        val declared = if (oversizeLength) 0x00FFFFFF else body.size + 8
        val bb = ByteBuffer.allocate(16 + body.size + 8)
        bb.putInt(0x000055AA).putInt(seq).putInt(cmd).putInt(declared).put(body)
        val crc = CRC32().apply { update(bb.array(), 0, 16 + body.size) }.value.toInt()
        bb.putInt(if (badChecksum) crc xor 0x1234 else crc).putInt(0x0000AA55)
        return bb.array()
    }

    private fun encrypt(b: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(b)

    private fun decrypt(b: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, key) }.doFinal(b)
    } catch (e: Exception) {
        null
    }

    private fun readFully(inp: java.io.InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = inp.read(b, off, b.size - off)
            if (r < 0) throw java.io.IOException("eof")
            off += r
        }
    }

    override fun close() { server.close() }
}
