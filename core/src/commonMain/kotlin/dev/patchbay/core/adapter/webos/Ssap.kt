package dev.patchbay.core.adapter.webos

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * LG webOS "SSAP" messages over WebSocket (docs/protocols/lg-webos.md). Pure and platform-free.
 * Reference: aiowebostv 1.0 (Apache-2.0), the library Home Assistant uses. We only ever use `wss://:3001`.
 */
public object Ssap {
    public const val WSS_PORT: Int = 3001
    public const val SSDP_ST: String = "urn:lge-com:service:webos-second-screen:1"

    /** Permissions requested at pairing. A subset of what aiowebostv asks for: only what we use. */
    public val PERMISSIONS: List<String> = listOf(
        "CONTROL_AUDIO", "CONTROL_POWER", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "READ_POWER_STATE",
        "READ_INPUT_DEVICE_LIST", "READ_RUNNING_APPS", "READ_APP_STATUS", "READ_TV_CURRENT_TIME", "LAUNCH",
        "WRITE_NOTIFICATION_TOAST", "READ_NETWORK_STATE",
    )

    private val json = Json { isLenient = false }

    public fun hello(): String = buildJsonObject { put("id", "hello"); put("type", "hello"); putJsonObject("payload") {} }.toString()

    public fun request(id: String, uri: String, payload: JsonObject = JsonObject(emptyMap())): String {
        require(uri.matches(Regex("^[a-zA-Z0-9./_-]{1,96}$"))) { "invalid SSAP uri" }
        return buildJsonObject {
            put("id", id); put("type", "request"); put("uri", "ssap://$uri"); put("payload", payload)
        }.toString()
    }

    /** The register message. [clientKey] is a secret: callers must never log the returned string. */
    public fun register(clientKey: String?): String = buildJsonObject {
        put("type", "register")
        put("id", "register_0")
        putJsonObject("payload") {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            clientKey?.let { put("client-key", it) }
            putJsonObject("manifest") {
                put("appVersion", "1.1")
                put("manifestVersion", 1)
                putJsonArray("permissions") { PERMISSIONS.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }.toString()

    public fun setVolume(id: String, level: Int): String = request(id, "audio/setVolume", buildJsonObject { put("volume", level) })
    public fun setMute(id: String, muted: Boolean): String = request(id, "audio/setMute", buildJsonObject { put("mute", muted) })
    public fun switchInput(id: String, inputId: String): String = request(id, "tv/switchInput", buildJsonObject { put("inputId", inputId) })

    // ---- inbound --------------------------------------------------------------------------------

    public sealed interface Incoming {
        public val id: String?

        public data class Hello(override val id: String?) : Incoming
        /** The TV is showing the pairing prompt (`pairingType: PROMPT`). */
        public data class PromptShown(override val id: String?) : Incoming
        /** Pairing accepted. [clientKey] is a secret. */
        public class Registered(override val id: String?, public val clientKey: String) : Incoming {
            override fun toString(): String = "Registered(id=$id, clientKey=<redacted>)"
        }
        public data class Response(override val id: String?, val payload: JsonObject) : Incoming
        public data class Error(override val id: String?, val message: String) : Incoming
    }

    public fun parse(text: String): PatchbayResult<Incoming> {
        if (text.length > 256 * 1024) return bad("message too large")
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return bad("not a JSON object")
        val type = root.str("type") ?: return bad("missing type")
        val id = root.str("id")
        val payload = when (val p = root["payload"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> p
            else -> return bad("payload must be an object")
        }
        return when (type) {
            "hello" -> PatchbayResult.Ok(Incoming.Hello(id))
            "registered" -> {
                val key = payload.str("client-key") ?: return bad("registered without client-key")
                if (!key.matches(Regex("^[0-9A-Za-z+/=_-]{8,128}$"))) return bad("malformed client-key")
                PatchbayResult.Ok(Incoming.Registered(id, key))
            }
            "response" -> {
                if (payload.str("pairingType") == "PROMPT" && id == "register_0") PatchbayResult.Ok(Incoming.PromptShown(id))
                else PatchbayResult.Ok(Incoming.Response(id, payload))
            }
            "error" -> PatchbayResult.Ok(Incoming.Error(id, (root.str("error") ?: "error").take(120)))
            else -> bad("unexpected type")
        }
    }

    /** Volume state from `audio/getVolume` or `audio/getStatus` (old and new firmware shapes). */
    public data class VolumeStatus(val level: Int, val muted: Boolean)

    public fun volume(p: JsonObject): VolumeStatus? {
        val src = (p["volumeStatus"] as? JsonObject) ?: p
        val level = (src["volume"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        val muted = ((src["muteStatus"] ?: src["mute"] ?: src["muted"]) as? JsonPrimitive)?.booleanOrNull ?: false
        if (level !in 0..100) return null
        return VolumeStatus(level, muted)
    }

    /** "Active" = on. "Screen Off" is also on (audio only). Everything else is standby/off. */
    public fun powerOn(p: JsonObject): Boolean? = when (p.str("state")) {
        "Active", "Screen Off", "Screen Saver" -> true
        "Active Standby", "Suspend", "Power Off" -> false
        null -> null
        else -> null
    }

    /** External inputs from `tv/getExternalInputList`: (id, label) pairs. */
    public fun inputs(p: JsonObject): List<Pair<String, String>> =
        (p["devices"] as? JsonArray).orEmpty().take(32).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            id to (o.str("label") ?: id)
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 256 }

    private fun bad(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("ssap: $why"))
}
