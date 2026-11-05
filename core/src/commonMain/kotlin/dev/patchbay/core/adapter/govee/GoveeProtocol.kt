package dev.patchbay.core.adapter.govee

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Govee LAN API codec (docs/protocols/govee.md). Pure and platform-free.
 *
 * Every inbound datagram is untrusted: messages are size- and depth-bounded, every known field is
 * type- and range-checked, and anything out of spec is **rejected**, not clamped. Unknown extra fields
 * are ignored so new firmware fields don't break parsing. The device's address always comes from the
 * UDP source, never from the `ip` field in the payload.
 */
public object GoveeProtocol {

    public const val SCAN_GROUP: String = "239.255.255.250"
    public const val SCAN_PORT: Int = 4001
    public const val LISTEN_PORT: Int = 4002
    public const val COMMAND_PORT: Int = 4003

    public const val MAX_MESSAGE_BYTES: Int = 2_048
    private const val MAX_DEPTH = 6
    public const val MIN_KELVIN: Int = 2_000
    public const val MAX_KELVIN: Int = 9_000

    private val json = Json { isLenient = false }

    // ---- outbound -------------------------------------------------------------------------------

    public val scan: ByteArray = envelope("scan") { put("account_topic", "reserve") }

    public val devStatus: ByteArray = envelope("devStatus") {}

    public fun turn(on: Boolean): ByteArray = envelope("turn") { put("value", if (on) 1 else 0) }

    /** [percent] 1–100. Govee treats 0 as invalid; switch off with [turn] instead. */
    public fun brightness(percent: Int): ByteArray {
        require(percent in 1..100) { "brightness must be 1..100" }
        return envelope("brightness") { put("value", percent) }
    }

    /** Sets an RGB colour (colour temperature 0 = "use the RGB value"). */
    public fun colour(rgb: Rgb): ByteArray = envelope("colorwc") {
        putJsonObject("color") { put("r", rgb.r); put("g", rgb.g); put("b", rgb.b) }
        put("colorTemInKelvin", 0)
    }

    public fun colourTemperature(kelvin: Kelvin): ByteArray {
        require(kelvin.value in MIN_KELVIN..MAX_KELVIN) { "colour temperature must be $MIN_KELVIN..$MAX_KELVIN K" }
        return envelope("colorwc") {
            putJsonObject("color") { put("r", 0); put("g", 0); put("b", 0) }
            put("colorTemInKelvin", kelvin.value)
        }
    }

    private fun envelope(cmd: String, data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray =
        buildJsonObject {
            putJsonObject("msg") {
                put("cmd", cmd)
                putJsonObject("data", data)
            }
        }.toString().encodeToByteArray()

    // ---- inbound --------------------------------------------------------------------------------

    public sealed interface Message {
        /** A scan reply. [deviceKey] is the normalised device ID (lowercase hex, no separators). */
        public data class ScanReply(
            val deviceKey: String,
            val sku: String,
            val claimedIp: String?,
            val wifiFirmware: String?,
            val bleFirmware: String?,
        ) : Message

        public data class Status(
            val on: Boolean,
            val brightness: Percent,
            val colour: Rgb?,
            val kelvin: Kelvin?,
        ) : Message {
            public fun toStates(): List<CapabilityState> = buildList {
                add(CapabilityState.PowerState(on))
                add(CapabilityState.BrightnessState(brightness))
                if (kelvin != null) add(CapabilityState.ColourTemperatureState(kelvin))
                else if (colour != null) add(CapabilityState.RgbState(colour))
            }
        }
    }

    public fun parse(bytes: ByteArray): PatchbayResult<Message> {
        if (bytes.isEmpty()) return violation("empty datagram")
        if (bytes.size > MAX_MESSAGE_BYTES) return violation("datagram larger than $MAX_MESSAGE_BYTES bytes")
        if (!depthWithin(bytes, MAX_DEPTH)) return violation("JSON nested too deeply")
        val text = bytes.decodeToString(throwOnInvalidSequence = false)
        if ('�' in text) return violation("not valid UTF-8")

        val root = try {
            json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            return violation("not valid JSON")
        }
        val msg = (root as? JsonObject)?.get("msg") as? JsonObject ?: return violation("missing msg object")
        val cmd = msg.str("cmd") ?: return violation("missing cmd")
        val data = msg["data"] as? JsonObject ?: return violation("missing data object")
        return when (cmd) {
            "scan" -> parseScan(data)
            "devStatus" -> parseStatus(data)
            else -> violation("unexpected cmd")
        }
    }

    private val deviceIdPattern = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5,7}$")
    private val skuPattern = Regex("^[A-Za-z0-9]{2,16}$")
    private val versionPattern = Regex("^[0-9A-Za-z._-]{1,32}$")

    private fun parseScan(d: JsonObject): PatchbayResult<Message> {
        val device = d.str("device") ?: return violation("scan: missing device")
        if (!deviceIdPattern.matches(device)) return violation("scan: malformed device id")
        val sku = d.str("sku") ?: return violation("scan: missing sku")
        if (!skuPattern.matches(sku)) return violation("scan: malformed sku")
        val ip = d.optStr("ip").getOr { return it }
        if (ip != null && ip.length > 45) return violation("scan: malformed ip")
        val wifi = d.optStr("wifiVersionSoft").getOr { return it }?.takeIf { versionPattern.matches(it) }
        val ble = d.optStr("bleVersionSoft").getOr { return it }?.takeIf { versionPattern.matches(it) }
        return PatchbayResult.Ok(Message.ScanReply(device.replace(":", "").lowercase(), sku.uppercase(), ip, wifi, ble))
    }

    private fun parseStatus(d: JsonObject): PatchbayResult<Message> {
        val onOff = d.int("onOff") ?: return violation("devStatus: missing onOff")
        if (onOff != 0L && onOff != 1L) return violation("devStatus: onOff must be 0 or 1")
        val brightness = d.int("brightness") ?: return violation("devStatus: missing brightness")
        if (brightness !in 0L..100L) return violation("devStatus: brightness out of range")

        val colour = when (val c = d["color"]) {
            null, JsonNull -> null
            is JsonObject -> {
                val r = c.int("r"); val g = c.int("g"); val b = c.int("b")
                if (r == null || g == null || b == null) return violation("devStatus: colour needs integer r, g, b")
                if (listOf(r, g, b).any { it !in 0L..255L }) return violation("devStatus: colour component out of range")
                Rgb(r.toInt(), g.toInt(), b.toInt())
            }
            else -> return violation("devStatus: colour must be an object")
        }
        val kelvin = when (val k = d["colorTemInKelvin"]) {
            null, JsonNull -> null
            else -> {
                val v = (k as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return violation("devStatus: colorTemInKelvin must be an integer")
                when (v) {
                    0L -> null // 0 means "RGB mode"
                    in MIN_KELVIN.toLong()..MAX_KELVIN.toLong() -> Kelvin(v.toInt())
                    else -> return violation("devStatus: colorTemInKelvin out of range")
                }
            }
        }
        return PatchbayResult.Ok(Message.Status(onOff == 1L, Percent(brightness.toDouble()), colour, kelvin))
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 64 }

    /** Ok(null) if absent; Err if present with the wrong type. */
    private fun JsonObject.optStr(key: String): PatchbayResult<String?> = when (val v = this[key]) {
        null, JsonNull -> PatchbayResult.Ok(null)
        is JsonPrimitive -> if (v.isString && v.content.length <= 64) PatchbayResult.Ok(v.content) else violation("$key must be a short string")
        else -> violation("$key must be a string")
    }

    /** Integer-valued JSON number only: rejects strings, booleans, fractions and exponents. */
    private fun JsonObject.int(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p.isString) return null
        val c = p.content
        if (c.isEmpty() || c.length > 10 || !(c.all { it.isDigit() } || (c[0] == '-' && c.length > 1 && c.drop(1).all { it.isDigit() }))) return null
        return c.toLongOrNull()
    }

    private inline fun <T> PatchbayResult<T>.getOr(onErr: (PatchbayResult.Err) -> Nothing): T = when (this) {
        is PatchbayResult.Ok -> value
        is PatchbayResult.Err -> onErr(this)
    }

    private fun violation(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("govee: $why"))

    /** Bracket depth check that respects JSON strings, so the parser never recurses deeply. */
    private fun depthWithin(bytes: ByteArray, max: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (b in bytes) {
            val ch = b.toInt().toChar()
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> if (++depth > max) return false
                '}', ']' -> depth--
            }
        }
        return true
    }
}
