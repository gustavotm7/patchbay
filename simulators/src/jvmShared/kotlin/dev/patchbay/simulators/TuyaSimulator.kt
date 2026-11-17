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
 * A fake Tuya bulb speaking local protocol 3.3, 3.4 or 3.5 ([version]; its own independent framing, CRC, HMAC and AES code, so a mistake in
 * the adapter's codec isn't mirrored here). Defaults to the standard "v2" layout (data points 20 to 24).
 *
 * 3.4 and 3.5 agree a session key first (client nonce, device nonce, an HMAC each way, then the key is derived from both nonces), after which
 * a connection can carry several messages; 3.3 answers one request per connection. Binds loopback only.
 * Fault injection: [offline], [badChecksum], [oversizeLength], [garbage], [versionHeaderInQuery].
 */
public class TuyaSimulator(
    public val address: String,
    public val deviceId: String,
    private val localKey: String,
    port: Int = 0,
    public val version: String = "3.3",
) : AutoCloseable {

    init {
        require(address.startsWith("127.")) { "simulators bind loopback only" }
        require(localKey.length == 16) { "local key is 16 characters" }
        require(version in listOf("3.3", "3.4", "3.5")) { "protocol 3.3, 3.4 or 3.5" }
    }

    /** How many session keys have been agreed (3.4 and 3.5), for tests. */
    @Volatile public var sessionsNegotiated: Int = 0
        private set

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
                thread(isDaemon = true) { if (version == "3.3") serve(c) else serveSecure(c) }
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

    // ---- protocols 3.4 and 3.5 -------------------------------------------------------------------

    private val rawKey: ByteArray = localKey.toByteArray(Charsets.US_ASCII)
    private val random = java.security.SecureRandom()

    private fun hmac(k: ByteArray, vararg parts: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(k, "HmacSHA256")); parts.forEach { update(it) } }.doFinal()

    private fun ecb(mode: Int, k: ByteArray, b: ByteArray, padding: String = "PKCS5Padding"): ByteArray =
        Cipher.getInstance("AES/ECB/$padding").apply { init(mode, SecretKeySpec(k, "AES")) }.doFinal(b)

    private fun gcm(mode: Int, k: ByteArray, iv: ByteArray, aad: ByteArray, b: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(k, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            if (aad.isNotEmpty()) updateAAD(aad)
        }.doFinal(b)

    private class Msg(val seq: Int, val cmd: Int, val plain: ByteArray)

    /** Reads and opens one client message under [k], or null if the client hung up or the message does not check out. */
    private fun readSecure(inp: java.io.InputStream, k: ByteArray): Msg? {
        if (version == "3.4") {
            val head = ByteArray(16).also { readFully(inp, it) }
            val bb = ByteBuffer.wrap(head)
            if (bb.getInt() != 0x000055AA) return null
            val seq = bb.getInt(); val cmd = bb.getInt(); val len = bb.getInt()
            if (len !in 36..8_192) return null
            val rest = ByteArray(len).also { readFully(inp, it) }
            val payload = rest.copyOf(len - 36)
            if (!hmac(k, head, payload).contentEquals(rest.copyOfRange(len - 36, len - 4))) return null
            return Msg(seq, cmd, ecb(Cipher.DECRYPT_MODE, k, payload))
        }
        val head = ByteArray(18).also { readFully(inp, it) }
        val bb = ByteBuffer.wrap(head)
        if (bb.getInt() != 0x00006699) return null
        bb.getShort()
        val seq = bb.getInt(); val cmd = bb.getInt(); val len = bb.getInt()
        if (len !in 28..8_192) return null // IV and tag around however little the client sent
        val rest = ByteArray(len + 4).also { readFully(inp, it) }
        val plain = gcm(Cipher.DECRYPT_MODE, k, rest.copyOf(12), head.copyOfRange(4, 18), rest.copyOfRange(12, len))
        return Msg(seq, cmd, plain)
    }

    /** One reply under [k]: a return code then [payload]. */
    private fun replySecure(seq: Int, cmd: Int, retcode: Int, payload: ByteArray, k: ByteArray): ByteArray {
        val declared: (Int) -> Int = { n -> if (oversizeLength) 0x00FFFFFF else n }
        if (version == "3.4") {
            val enc = if (payload.isEmpty()) payload else ecb(Cipher.ENCRYPT_MODE, k, payload)
            val head = ByteBuffer.allocate(16).putInt(0x000055AA).putInt(seq).putInt(cmd).putInt(declared(4 + enc.size + 36)).array()
            val body = ByteBuffer.allocate(4 + enc.size).putInt(retcode).put(enc).array()
            val mac = hmac(k, head, body).also { if (badChecksum) it[31] = (it[31].toInt() xor 1).toByte() }
            return head + body + mac + byteArrayOf(0, 0, 0xAA.toByte(), 0x55)
        }
        val plain = ByteBuffer.allocate(4 + payload.size).putInt(retcode).put(payload).array()
        val iv = ByteArray(12).also(random::nextBytes)
        val head = ByteBuffer.allocate(18).putInt(0x00006699).putShort(0).putInt(seq).putInt(cmd).putInt(declared(12 + plain.size + 16)).array()
        val sealed = gcm(Cipher.ENCRYPT_MODE, k, iv, head.copyOfRange(4, 18), plain).also { if (badChecksum) it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        return head + iv + sealed + byteArrayOf(0, 0, 0x99.toByte(), 0x66)
    }

    private fun serveSecure(c: java.net.Socket) = c.use {
        it.soTimeout = 2_000
        try {
            if (offline) return@use
            val inp = it.getInputStream(); val out = it.getOutputStream()
            var key = rawKey
            var clientNonce: ByteArray? = null
            var deviceNonce: ByteArray? = null
            val header = "$version".toByteArray() + ByteArray(12)
            while (true) {
                val m = readSecure(inp, key) ?: return@use
                if (garbage) { out.write(ByteArray(40) { i -> (i * 7).toByte() }); return@use }
                when (m.cmd) {
                    0x03 -> {
                        if (m.plain.size != 16) return@use
                        clientNonce = m.plain
                        deviceNonce = ByteArray(16).also(random::nextBytes)
                        out.write(replySecure(m.seq, 0x04, 0, deviceNonce + hmac(rawKey, m.plain), key)); out.flush()
                    }
                    0x05 -> {
                        val cn = clientNonce; val dn = deviceNonce
                        if (cn == null || dn == null || !hmac(rawKey, dn).contentEquals(m.plain)) return@use // the client does not hold the key
                        val mixed = ByteArray(16) { i -> (cn[i].toInt() xor dn[i].toInt()).toByte() }
                        key = if (version == "3.4") ecb(Cipher.ENCRYPT_MODE, rawKey, mixed, "NoPadding")
                        else gcm(Cipher.ENCRYPT_MODE, rawKey, cn.copyOf(12), ByteArray(0), mixed).copyOf(16)
                        sessionsNegotiated++
                    }
                    0x10 -> {
                        val json = """{"data":{"dps":${JsonObject(dps.toMap())}},"protocol":4,"t":1700000000}""".toByteArray()
                        out.write(replySecure(m.seq, 0x10, 0, if (versionHeaderInQuery) header + json else json, key)); out.flush()
                    }
                    0x0D -> {
                        val body = if (m.plain.size > 15 && m.plain.copyOf(15).contentEquals(header)) m.plain.copyOfRange(15, m.plain.size) else null
                        val obj = body?.let { b -> Json.parseToJsonElement(b.decodeToString()).jsonObject }
                        val w = ((obj?.get("data") as? JsonObject)?.get("dps") as? JsonObject)?.mapValues { e -> e.value as JsonPrimitive }
                        if (w == null) out.write(replySecure(m.seq, 0x0D, 1, "data format error".toByteArray(), key))
                        else {
                            writes += w
                            dps.putAll(w)
                            out.write(replySecure(m.seq, 0x0D, 0, ByteArray(0), key))
                        }
                        out.flush()
                    }
                    else -> { out.write(replySecure(m.seq, m.cmd, 1, ByteArray(0), key)); out.flush() }
                }
            }
        } catch (e: Exception) {
            // malformed, wrong key, or closed: hang up
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
