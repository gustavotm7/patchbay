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
 * lives in `TuyaCipher` (jvmShared). The 3.4 and 3.5 frames (HMAC, AES-GCM, session keys) are in `TuyaSecureFrames` and
 * `TuyaLinks` (jvmShared); the JSON messages for them are here.
 *
 * Frame: `00 00 55 AA | seq | cmd | length | [retcode (device to client)] payload | CRC-32 | 00 00 AA 55`, where
 * length counts everything after the 16-byte header. Every frame from the device is untrusted: lengths are bounded,
 * the CRC and both markers are checked, and unknown JSON fields are ignored.
 */
public object TuyaProtocol {

    public const val PORT: Int = 6668
    public const val HEADER_BYTES: Int = 16
    public const val MAX_PAYLOAD: Int = 16_384

    /** A string data point longer than this (a raw cleaning map, say) is skipped on its own; the rest of the report is kept. */
    public const val MAX_DP_STRING: Int = 1_024
    private const val PREFIX = 0x000055AA
    private const val SUFFIX = 0x0000AA55

    public const val CMD_CONTROL: Int = 0x07
    public const val CMD_STATUS: Int = 0x08
    public const val CMD_DP_QUERY: Int = 0x0A

    /** Protocol 3.4 and 3.5 use these instead of [CMD_CONTROL] and [CMD_DP_QUERY], after a session key has been agreed with the device. */
    public const val CMD_CONTROL_NEW: Int = 0x0D
    public const val CMD_DP_QUERY_NEW: Int = 0x10
    public const val CMD_SESSION_START: Int = 0x03
    public const val CMD_SESSION_RESPONSE: Int = 0x04
    public const val CMD_SESSION_FINISH: Int = 0x05

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

    /** The query a "device22" device wants: the data points listed by number, each with a null value. */
    public fun dpQueryByNumberJson(deviceId: String, dpIds: List<String>, nowSeconds: Long): ByteArray = buildJsonObject {
        put("devId", deviceId); put("uid", deviceId); put("t", nowSeconds.toString())
        put("dps", JsonObject(dpIds.associateWith { kotlinx.serialization.json.JsonNull }))
    }.toString().encodeToByteArray()

    /** Protocol 3.4 and 3.5 ask for the data points with an empty JSON object. */
    public fun dpQueryJsonNew(): ByteArray = "{}".encodeToByteArray()

    /** Protocol 3.4 and 3.5 control message: the data points sit under `data.dps`. */
    public fun controlJsonNew(dps: Map<String, JsonPrimitive>, nowSeconds: Long): ByteArray = buildJsonObject {
        put("protocol", 5); put("t", nowSeconds)
        put("data", buildJsonObject { put("dps", JsonObject(dps)) })
    }.toString().encodeToByteArray()

    /** The marker that precedes control payloads and sometimes replies: the version ("3.3", "3.4" or "3.5") and twelve zero bytes. */
    public fun versionHeader(label: String): ByteArray = label.encodeToByteArray() + ByteArray(12)

    /** Drops the version marker ("3.3", "3.4" or "3.5" and twelve zero bytes) if the device included one. */
    public fun stripVersionHeader(payload: ByteArray): ByteArray =
        if (payload.size > VERSION_HEADER.size && payload[0] == '3'.code.toByte() && payload[1] == '.'.code.toByte() &&
            payload[2] in '3'.code.toByte()..'5'.code.toByte() && (3 until VERSION_HEADER.size).all { payload[it] == 0.toByte() }
        ) payload.copyOfRange(VERSION_HEADER.size, payload.size) else payload

    /** Parses decrypted JSON into data points. Keys are 1–3 digits; values are booleans, integers or strings up to [MAX_DP_STRING]; anything else is skipped. */
    public fun parseDps(plain: ByteArray): PatchbayResult<Map<String, JsonPrimitive>> {
        if (plain.isEmpty() || plain.size > MAX_PAYLOAD) return violation("bad json size")
        val root = try {
            json.parseToJsonElement(plain.decodeToString(throwOnInvalidSequence = true)).jsonObject
        } catch (e: Exception) {
            return violation("not json")
        }
        // 3.3 puts the data points at the top; 3.4 and 3.5 put them under "data".
        val dps = root["dps"] as? JsonObject ?: (root["data"] as? JsonObject)?.get("dps") as? JsonObject ?: return violation("no dps")
        if (dps.size > 64) return violation("too many dps")
        val out = LinkedHashMap<String, JsonPrimitive>()
        for ((k, v) in dps) {
            // One odd data point must not cost the whole report: robot vacuums send long raw (base64) values and sometimes nested ones. Skip what we cannot
            // use (bounded: at most 64 entries and the payload cap above) and keep the rest.
            if (k.length !in 1..3 || !k.all { it in '0'..'9' }) continue
            val p = v as? JsonPrimitive ?: continue
            if (p.isString && p.content.length > MAX_DP_STRING) continue
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
        PatchbayResult.Err(PatchbayError.ProtocolViolation("tuya: $why. The device may use a different protocol version than the one Patchbay is speaking to it"))
}
