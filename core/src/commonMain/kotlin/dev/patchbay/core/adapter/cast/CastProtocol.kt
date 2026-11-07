package dev.patchbay.core.adapter.cast

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Cast v2 sender protocol (docs/protocols/cast.md): a 4-byte big-endian length, then a protobuf `CastMessage`
 * (protocol version, source id, destination id, namespace, payload type, string payload). Payloads are JSON in named
 * namespaces. Only what Patchbay needs: connect, heartbeat, receiver status, volume, and media status and transport.
 * Pure and platform-free. Every message from the device is untrusted: bounded, strictly shaped, unknown fields ignored.
 */
public object CastProtocol {

    public const val PORT: Int = 8009
    public const val MAX_MESSAGE_BYTES: Int = 65_536
    private const val MAX_FIELD = 60_000

    public const val NS_CONNECTION: String = "urn:x-cast:com.google.cast.tp.connection"
    public const val NS_HEARTBEAT: String = "urn:x-cast:com.google.cast.tp.heartbeat"
    public const val NS_RECEIVER: String = "urn:x-cast:com.google.cast.receiver"
    public const val NS_MEDIA: String = "urn:x-cast:com.google.cast.media"

    public const val SENDER: String = "sender-patchbay"
    public const val RECEIVER: String = "receiver-0"

    private val json = Json { isLenient = false }

    public data class Message(val source: String, val destination: String, val namespace: String, val payload: String)

    // ---- framing ------------------------------------------------------------------------------------

    /** Length prefix plus the protobuf-encoded message. */
    public fun frame(m: Message): ByteArray {
        val body = encode(m)
        require(body.size <= MAX_MESSAGE_BYTES) { "message too large" }
        val n = body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + body
    }

    public fun frameLength(header: ByteArray): PatchbayResult<Int> {
        if (header.size != 4) return violation("short header")
        val n = ((header[0].toInt() and 0xFF) shl 24) or ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        return if (n in 1..MAX_MESSAGE_BYTES) PatchbayResult.Ok(n) else violation("declared length out of range")
    }

    private fun encode(m: Message): ByteArray {
        val out = ArrayList<Byte>()
        fun varint(v0: Int) { var v = v0; while (v >= 0x80) { out += ((v and 0x7F) or 0x80).toByte(); v = v ushr 7 }; out += v.toByte() }
        fun str(field: Int, s: String) { val b = s.encodeToByteArray(); out += ((field shl 3) or 2).toByte(); varint(b.size); b.forEach { out += it } }
        out += 0x08; out += 0x00 // protocol_version = CASTV2_1_0
        str(2, m.source); str(3, m.destination); str(4, m.namespace)
        out += 0x28; out += 0x00 // payload_type = STRING
        str(6, m.payload)
        return out.toByteArray()
    }

    /** Decodes a protobuf CastMessage body. Binary payloads are rejected (Patchbay never needs them). */
    public fun decode(body: ByteArray): PatchbayResult<Message> {
        if (body.isEmpty() || body.size > MAX_MESSAGE_BYTES) return violation("size")
        var i = 0
        fun varint(): Int? {
            var result = 0; var shift = 0
            while (i < body.size && shift <= 28) {
                val b = body[i++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
        var source: String? = null; var dest: String? = null; var ns: String? = null; var payload: String? = null; var payloadType = 0
        while (i < body.size) {
            val tag = varint() ?: return violation("bad tag")
            val field = tag ushr 3
            when (tag and 7) {
                0 -> { val v = varint() ?: return violation("bad varint"); if (field == 5) payloadType = v }
                1 -> { if (i + 8 > body.size) return violation("truncated"); i += 8 }
                5 -> { if (i + 4 > body.size) return violation("truncated"); i += 4 }
                2 -> {
                    val len = varint() ?: return violation("bad length")
                    if (len < 0 || len > MAX_FIELD || i + len > body.size) return violation("field length")
                    val bytes = body.copyOfRange(i, i + len); i += len
                    val text = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (e: Exception) { if (field in 2..4 || field == 6) return violation("not text") else null }
                    when (field) { 2 -> source = text; 3 -> dest = text; 4 -> ns = text; 6 -> payload = text }
                }
                else -> return violation("unsupported wire type")
            }
        }
        if (payloadType != 0) return PatchbayResult.Err(PatchbayError.Unsupported("binary cast payload"))
        if (source == null || dest == null || ns == null || payload == null) return violation("missing field")
        if (source.length > 128 || dest.length > 128 || ns.length > 128) return violation("id too long")
        return PatchbayResult.Ok(Message(source, dest, ns, payload))
    }

    // ---- outbound JSON ------------------------------------------------------------------------------

    public fun message(destination: String, namespace: String, payload: String): Message = Message(SENDER, destination, namespace, payload)

    public const val CONNECT: String = """{"type":"CONNECT"}"""
    public const val PING: String = """{"type":"PING"}"""
    public const val PONG: String = """{"type":"PONG"}"""

    public fun getStatus(requestId: Int): String = """{"type":"GET_STATUS","requestId":$requestId}"""

    public fun setVolume(level: Double, requestId: Int): String = buildJsonObject {
        put("type", "SET_VOLUME"); put("requestId", requestId); putJsonObject("volume") { put("level", level.coerceIn(0.0, 1.0)) }
    }.toString()

    public fun setMuted(muted: Boolean, requestId: Int): String = buildJsonObject {
        put("type", "SET_VOLUME"); put("requestId", requestId); putJsonObject("volume") { put("muted", muted) }
    }.toString()

    /** [type] is PLAY, PAUSE or STOP: the only transport actions Patchbay sends. */
    public fun mediaCommand(type: String, mediaSessionId: Int, requestId: Int): String {
        require(type == "PLAY" || type == "PAUSE" || type == "STOP") { "unsupported media command" }
        return buildJsonObject { put("type", type); put("requestId", requestId); put("mediaSessionId", mediaSessionId) }.toString()
    }

    // ---- inbound JSON -------------------------------------------------------------------------------

    public fun messageType(payload: String): String? = parseObject(payload)?.get("type").text()

    public data class ReceiverStatus(
        val volume: Double?,
        val muted: Boolean?,
        val appName: String?,
        val transportId: String?,
    )

    /** Parses a RECEIVER_STATUS payload. Out-of-range volumes are rejected, never clamped. */
    public fun parseReceiverStatus(payload: String): PatchbayResult<ReceiverStatus> {
        val root = parseObject(payload) ?: return violation("not a JSON object")
        if (root["type"].text() != "RECEIVER_STATUS") return violation("not a receiver status")
        val status = root["status"] as? JsonObject ?: return violation("no status")
        val vol = status["volume"] as? JsonObject
        val level = vol?.get("level")?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: return violation("volume level type") }
        if (level != null && (level < 0.0 || level > 1.0)) return violation("volume level out of range")
        val muted = vol?.get("muted")?.let { (it as? JsonPrimitive)?.booleanOrNull ?: return violation("muted type") }
        val app = (status["applications"] as? JsonArray)?.firstOrNull() as? JsonObject
        return PatchbayResult.Ok(ReceiverStatus(level, muted, app?.get("displayName").text(), app?.get("transportId").text()))
    }

    public data class MediaStatus(val playerState: String, val mediaSessionId: Int, val positionSec: Double?, val durationSec: Double?, val title: String?, val artist: String?)

    /** Parses a MEDIA_STATUS payload; null when it carries no session (nothing playing). */
    public fun parseMediaStatus(payload: String): PatchbayResult<MediaStatus?> {
        val root = parseObject(payload) ?: return violation("not a JSON object")
        if (root["type"].text() != "MEDIA_STATUS") return violation("not a media status")
        val first = (root["status"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return PatchbayResult.Ok(null)
        val state = first["playerState"].text() ?: return violation("no player state")
        if (state !in PLAYER_STATES) return violation("unknown player state")
        val id = (first["mediaSessionId"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return violation("no media session id")
        fun num(e: kotlinx.serialization.json.JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it >= 0.0 && it < 1e7 }
        val media = first["media"] as? JsonObject
        val meta = media?.get("metadata") as? JsonObject
        return PatchbayResult.Ok(MediaStatus(state, id, num(first["currentTime"]), num(media?.get("duration")), meta?.get("title").text(), meta?.get("artist").text()))
    }

    private val PLAYER_STATES = setOf("IDLE", "PLAYING", "PAUSED", "BUFFERING")

    // ---- helpers ------------------------------------------------------------------------------------

    private fun parseObject(s: String): JsonObject? {
        if (s.isEmpty() || s.length > MAX_MESSAGE_BYTES) return null
        if (s.count { it == '{' || it == '[' } > 64) return null
        return try { json.parseToJsonElement(s).jsonObject } catch (e: Exception) { null }
    }

    private fun kotlinx.serialization.json.JsonElement?.text(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= 256 && it.none { c -> c.isISOControl() } }

    private fun violation(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("cast: $why"))
}
