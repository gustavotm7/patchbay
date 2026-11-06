package dev.patchbay.core.adapter.kasa

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * TP-Link Kasa "legacy" local protocol (docs/protocols/kasa.md): JSON on TCP/UDP 9999, obfuscated with an
 * XOR autokey stream (initial key 171). It is not encryption and has no authentication; it only hides
 * the JSON from casual capture. Newer firmware speaks KLAP and needs the owner's TP-Link account hash.
 * That is out of scope until an ADR approves storing it, so such plugs are simply not found here.
 *
 * Pure and platform-free. Every inbound message is untrusted: size-bounded, type- and range-checked,
 * rejected (never clamped) when out of spec. Unknown fields are ignored.
 */
public object KasaProtocol {

    public const val PORT: Int = 9999
    public const val MAX_MESSAGE_BYTES: Int = 4_096
    private const val INITIAL_KEY: Int = 171
    private const val MAX_TEXT = 64

    private val json = Json { isLenient = false }

    // ---- obfuscation ----------------------------------------------------------------------------

    public fun scramble(plain: ByteArray): ByteArray {
        var key = INITIAL_KEY
        return ByteArray(plain.size) { i ->
            val out = key xor (plain[i].toInt() and 0xFF)
            key = out
            out.toByte()
        }
    }

    public fun unscramble(cipher: ByteArray): ByteArray {
        var key = INITIAL_KEY
        return ByteArray(cipher.size) { i ->
            val c = cipher[i].toInt() and 0xFF
            val out = key xor c
            key = c
            out.toByte()
        }
    }

    /** TCP framing: 4-byte big-endian length, then the scrambled JSON. */
    public fun frame(message: String): ByteArray {
        val body = scramble(message.encodeToByteArray())
        require(body.size <= MAX_MESSAGE_BYTES) { "message too large" }
        val n = body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + body
    }

    /** Reads the declared length from a TCP header. Rejects anything over [MAX_MESSAGE_BYTES]. */
    public fun frameLength(header: ByteArray): PatchbayResult<Int> {
        if (header.size != 4) return violation("short header")
        val n = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        return if (n in 2..MAX_MESSAGE_BYTES) PatchbayResult.Ok(n) else violation("declared length out of range")
    }

    // ---- outbound -------------------------------------------------------------------------------

    public const val GET_SYSINFO: String = """{"system":{"get_sysinfo":{}}}"""

    public fun setRelay(on: Boolean): String = """{"system":{"set_relay_state":{"state":${if (on) 1 else 0}}}}"""

    /** UDP discovery datagrams carry the scrambled JSON with no length prefix. */
    public val discoveryDatagram: ByteArray = scramble(GET_SYSINFO.encodeToByteArray())

    // ---- inbound --------------------------------------------------------------------------------

    public data class Sysinfo(
        val deviceKey: String,
        val alias: String,
        val model: String,
        val firmware: String?,
        val relayOn: Boolean,
    )

    /** Parses a get_sysinfo reply that has already been unscrambled. Accepts single-outlet plugs and switches only. */
    public fun parseSysinfo(plain: ByteArray): PatchbayResult<Sysinfo> {
        if (plain.size > MAX_MESSAGE_BYTES) return violation("too large")
        val root = parseObject(plain) ?: return violation("not a JSON object")
        val info = (root["system"] as? JsonObject)?.get("get_sysinfo") as? JsonObject ?: return violation("no get_sysinfo")
        if (info["err_code"].int() ?: 0 != 0) return violation("device reported an error")
        if (info.containsKey("children")) return PatchbayResult.Err(PatchbayError.Unsupported("multi-outlet Kasa strips aren't supported yet"))
        val type = info["type"].text() ?: info["mic_type"].text() ?: return violation("no type")
        if (type != "IOT.SMARTPLUGSWITCH") return PatchbayResult.Err(PatchbayError.Unsupported("not a Kasa plug or switch ($type)"))
        val relay = info["relay_state"].int() ?: return violation("relay_state missing")
        if (relay != 0 && relay != 1) return violation("relay_state out of range")
        val id = info["deviceId"].text()?.takeIf { it.length == 40 && it.all(::isHex) }
            ?: info["mac"].text()?.filter { it != ':' && it != '-' }?.takeIf { it.length == 12 && it.all(::isHex) }
            ?: return violation("no usable device id")
        val model = info["model"].text() ?: return violation("model missing")
        val alias = info["alias"].text() ?: ""
        return PatchbayResult.Ok(Sysinfo(id.lowercase(), alias, model, info["sw_ver"].text(), relay == 1))
    }

    /** Parses a set_relay_state reply. Ok only when err_code is 0. */
    public fun parseSetRelay(plain: ByteArray): PatchbayResult<Unit> {
        val root = parseObject(plain) ?: return violation("not a JSON object")
        val r = (root["system"] as? JsonObject)?.get("set_relay_state") as? JsonObject ?: return violation("no set_relay_state")
        val code = r["err_code"].int() ?: return violation("err_code missing")
        return if (code == 0) PatchbayResult.Ok(Unit) else PatchbayResult.Err(PatchbayError.Unreachable("plug refused the command (err_code $code)"))
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun parseObject(bytes: ByteArray): JsonObject? {
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return null
        if (bytes.count { it == '{'.code.toByte() || it == '['.code.toByte() } > 32) return null
        return try {
            json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** Integer-valued JSON number only: rejects strings, booleans and fractions. */
    private fun kotlinx.serialization.json.JsonElement?.int(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun kotlinx.serialization.json.JsonElement?.text(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.length <= MAX_TEXT && it.none { c -> c.isISOControl() } }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun violation(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("kasa: $why"))
}
