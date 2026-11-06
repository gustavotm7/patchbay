package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Tuya local protocol 3.3 framing and messages (docs/protocols/tuya.md, ADR 0007). Pure and platform-free; the AES
 * lives in `TuyaCipher` (jvmShared). Protocol 3.4 and 3.5 (session keys, HMAC, AES-GCM) are not implemented.
 *
 * Frame: `00 00 55 AA | seq | cmd | length | [retcode (device to client)] payload | CRC-32 | 00 00 AA 55`, where
 * length counts everything after the 16-byte header. Every frame from the device is untrusted: lengths are bounded,
 * the CRC and both markers are checked, and unknown JSON fields are ignored.
 */
public object TuyaProtocol {

    public const val PORT: Int = 6668
    public const val HEADER_BYTES: Int = 16
    public const val MAX_PAYLOAD: Int = 4_096
    private const val PREFIX = 0x000055AA
    private const val SUFFIX = 0x0000AA55

    public const val CMD_CONTROL: Int = 0x07
    public const val CMD_STATUS: Int = 0x08
    public const val CMD_DP_QUERY: Int = 0x0A

    /** Version marker that precedes the ciphertext in control frames: "3.3" and twelve zero bytes. */
    public val VERSION_HEADER: ByteArray = "3.3".encodeToByteArray() + ByteArray(12)

    private val json = Json { isLenient = false }

    public class Frame(public val seq: Int, public val cmd: Int, public val retcode: Int?, public val payload: ByteArray)

    public fun encode(seq: Int, cmd: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val out = ByteArray(HEADER_BYTES + payload.size + 8)
        putInt(out, 0, PREFIX); putInt(out, 4, seq); putInt(out, 8, cmd); putInt(out, 12, payload.size + 8)
        payload.copyInto(out, HEADER_BYTES)
        val crcAt = HEADER_BYTES + payload.size
        putInt(out, crcAt, crc32(out, 0, crcAt))
        putInt(out, crcAt + 4, SUFFIX)
        return out
    }

    /** Validates a 16-byte header and returns how many more bytes to read. */
    public fun remainingAfterHeader(header: ByteArray): PatchbayResult<Int> {
        if (header.size != HEADER_BYTES) return violation("short header")
        if (getInt(header, 0) != PREFIX) return violation("bad prefix")
        val n = getInt(header, 12)
        return if (n in 8..(MAX_PAYLOAD + 12)) PatchbayResult.Ok(n) else violation("length out of range")
    }

    /** Decodes a whole frame. [fromDevice] frames begin with a 4-byte return code. */
    public fun decode(frame: ByteArray, fromDevice: Boolean): PatchbayResult<Frame> {
        if (frame.size < HEADER_BYTES + 8) return violation("short frame")
        if (getInt(frame, 0) != PREFIX) return violation("bad prefix")
        val n = getInt(frame, 12)
        if (n != frame.size - HEADER_BYTES) return violation("length mismatch")
        if (getInt(frame, frame.size - 4) != SUFFIX) return violation("bad suffix")
        if (getInt(frame, frame.size - 8) != crc32(frame, 0, frame.size - 8)) return violation("bad checksum")
        val body = frame.copyOfRange(HEADER_BYTES, frame.size - 8)
        return if (fromDevice) {
            if (body.size < 4) return violation("no return code")
            PatchbayResult.Ok(Frame(getInt(frame, 4), getInt(frame, 8), getInt(body, 0), body.copyOfRange(4, body.size)))
        } else {
            PatchbayResult.Ok(Frame(getInt(frame, 4), getInt(frame, 8), null, body))
        }
    }

    // ---- messages ---------------------------------------------------------------------------------

    public fun dpQueryJson(deviceId: String, nowSeconds: Long): ByteArray = buildJsonObject {
        put("gwId", deviceId); put("devId", deviceId); put("uid", deviceId); put("t", nowSeconds.toString())
    }.toString().encodeToByteArray()

    public fun controlJson(deviceId: String, dps: Map<String, JsonPrimitive>, nowSeconds: Long): ByteArray = buildJsonObject {
        put("devId", deviceId); put("uid", deviceId); put("t", nowSeconds.toString())
        put("dps", JsonObject(dps))
    }.toString().encodeToByteArray()

    /** Drops the "3.3" version marker if the device included one. */
    public fun stripVersionHeader(payload: ByteArray): ByteArray =
        if (payload.size > VERSION_HEADER.size && payload.copyOf(3).contentEquals(VERSION_HEADER.copyOf(3))) payload.copyOfRange(VERSION_HEADER.size, payload.size) else payload

    /** Parses decrypted JSON into data points. Keys are 1–3 digits; values are booleans, integers or short strings. */
    public fun parseDps(plain: ByteArray): PatchbayResult<Map<String, JsonPrimitive>> {
        if (plain.isEmpty() || plain.size > MAX_PAYLOAD) return violation("bad json size")
        val root = try {
            json.parseToJsonElement(plain.decodeToString(throwOnInvalidSequence = true)).jsonObject
        } catch (e: Exception) {
            return violation("not json")
        }
        val dps = root["dps"] as? JsonObject ?: return violation("no dps")
        if (dps.size > 64) return violation("too many dps")
        val out = LinkedHashMap<String, JsonPrimitive>()
        for ((k, v) in dps) {
            if (k.length !in 1..3 || !k.all { it in '0'..'9' }) return violation("bad dp id")
            val p = v as? JsonPrimitive ?: return violation("dp value type")
            if (p.isString && p.content.length > 256) return violation("dp value too long")
            out[k] = p
        }
        return PatchbayResult.Ok(out)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    public fun crc32(b: ByteArray, off: Int, len: Int): Int {
        var c = -1
        for (i in off until off + len) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        }
        return c.inv()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun getInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun violation(why: String): PatchbayResult.Err =
        PatchbayResult.Err(PatchbayError.ProtocolViolation("tuya: $why. The device may use a protocol version Patchbay doesn't support yet (only 3.3)"))
}
