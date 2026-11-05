package dev.patchbay.core.adapter.bambu

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Celsius
import dev.patchbay.core.model.FilamentSlot
import dev.patchbay.core.model.HeaterReading
import dev.patchbay.core.model.HmsEvent
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.PrintAction
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.model.Severity
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bambu Lab LAN MQTT reports (docs/protocols/bambu.md). Pure and platform-free.
 *
 * Printers send one full `pushall` report and then **incremental** updates containing only changed fields,
 * so state is kept by [merge] and read by [toStates]. Every value is range-checked; anything implausible
 * is dropped rather than shown.
 *
 * Safety scope (brief §4): the only commands built here are pause, resume, stop and lights. There is no
 * heating, motion or G-code builder.
 */
public object BambuReport {
    public const val MQTT_PORT: Int = 8883
    public const val USERNAME: String = "bblp"
    public const val SSDP_ST: String = "urn:bambulab-com:device:3dprinter:1"
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_DEPTH = 8

    private val json = Json { isLenient = false }

    public fun reportTopic(serial: String): String = "device/${checkSerial(serial)}/report"
    public fun requestTopic(serial: String): String = "device/${checkSerial(serial)}/request"

    private val serialPattern = Regex("^[0-9A-Z]{8,20}$")
    public fun isSerial(s: String): Boolean = serialPattern.matches(s)
    private fun checkSerial(s: String): String = s.also { require(isSerial(it)) { "invalid serial" } }

    public val pushAll: ByteArray = buildJsonObject { putJsonObject("pushing") { put("sequence_id", "0"); put("command", "pushall") } }.toString().encodeToByteArray()

    public fun control(action: PrintAction): ByteArray = buildJsonObject {
        putJsonObject("print") {
            put("sequence_id", "0")
            put("command", when (action) { PrintAction.PAUSE -> "pause"; PrintAction.RESUME -> "resume"; PrintAction.STOP -> "stop" })
        }
    }.toString().encodeToByteArray()

    /** Chamber light(s). The H2D has two (`chamber_light`, `chamber_light2`); both are switched together. */
    public fun light(node: String, on: Boolean): ByteArray {
        require(node in setOf("chamber_light", "chamber_light2")) { "unknown light" }
        return buildJsonObject {
            putJsonObject("system") {
                put("sequence_id", "0"); put("command", "ledctrl"); put("led_node", node); put("led_mode", if (on) "on" else "off")
                put("led_on_time", 500); put("led_off_time", 500); put("loop_times", 0); put("interval_time", 0)
            }
        }.toString().encodeToByteArray()
    }

    /** Parses one MQTT payload. Returns the `print` object (the part we track), or an error. */
    public fun parse(bytes: ByteArray): PatchbayResult<JsonObject?> {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return bad("payload size")
        if (!bracketDepthWithin(bytes, MAX_DEPTH)) return bad("too deeply nested") // before the recursive parser
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return bad("not a JSON object")
        if (depth(root) > MAX_DEPTH) return bad("too deeply nested")
        return PatchbayResult.Ok(root["print"] as? JsonObject)
    }

    /** Deep-merges an incremental update into the current state (objects merge; everything else replaces). */
    public fun merge(state: JsonObject, update: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(state)
        for ((k, v) in update) {
            val old = out[k]
            out[k] = if (old is JsonObject && v is JsonObject) merge(old, v) else v
        }
        return JsonObject(out)
    }

    public fun toStates(p: JsonObject): List<CapabilityState> = buildList {
        job(p)?.let(::add)
        thermals(p)?.let(::add)
        slots(p)?.let(::add)
        light(p)?.let(::add)
    }

    private fun job(p: JsonObject): CapabilityState.PrintJobState? {
        val stage = when (p.str("gcode_state")) {
            "IDLE" -> PrintStage.IDLE
            "PREPARE", "SLICING" -> PrintStage.PREPARING
            "RUNNING" -> PrintStage.PRINTING
            "PAUSE" -> PrintStage.PAUSED
            "FINISH" -> PrintStage.FINISHED
            "FAILED" -> PrintStage.FAILED
            null -> return null
            else -> PrintStage.IDLE
        }
        val total = p.int("total_layer_num")?.takeIf { it in 0..100_000 }?.toInt() ?: 0
        val layer = p.int("layer_num")?.takeIf { it in 0..100_000 }?.toInt()?.coerceAtMost(total) ?: 0
        val pct = p.int("mc_percent")?.takeIf { it in 0..100 }?.toDouble() ?: 0.0
        val remaining = p.int("mc_remaining_time")?.takeIf { it in 0..100_000 }?.toInt()
        val hms = (p["hms"] as? JsonArray).orEmpty().take(16).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val attr = o.int("attr") ?: return@mapNotNull null
            val code = o.int("code") ?: return@mapNotNull null
            hmsEvent(attr, code)
        }
        return CapabilityState.PrintJobState(
            stage = stage, fileName = p.str("subtask_name")?.take(120), progress = Percent(pct), layer = layer,
            totalLayers = total, remainingMinutes = remaining, hms = hms,
        )
    }

    /** HMS code "HMS_AAAA_AAAA_CCCC_CCCC" from (attr, code); severity from the code's high word (Bambu wiki). */
    internal fun hmsEvent(attr: Long, code: Long): HmsEvent {
        fun hex(v: Long) = v.toString(16).uppercase().padStart(8, '0')
        val a = hex(attr); val c = hex(code)
        val text = "HMS_${a.take(4)}_${a.drop(4)}_${c.take(4)}_${c.drop(4)}"
        val level = (code shr 16) and 0xFFFF
        val severity = when (level) { 1L, 2L -> Severity.FAULT; 3L -> Severity.WARNING; else -> Severity.INFO }
        return HmsEvent(text, severity, "See the code on the printer or in Bambu's HMS wiki")
    }

    private fun thermals(p: JsonObject): CapabilityState.PrinterThermalsState? {
        val dev = p["device"] as? JsonObject
        // H2D/H2-series: per-nozzle packed words (low = current, high = target) under device.extruder.info[].
        val packedNozzles = ((dev?.get("extruder") as? JsonObject)?.get("info") as? JsonArray).orEmpty().take(4).mapNotNull { e ->
            (e as? JsonObject)?.int("temp")?.let(::unpack)
        }
        val nozzles = packedNozzles.ifEmpty {
            val cur = p.num("nozzle_temper")?.let(::celsius) ?: return null
            listOf(HeaterReading(cur, p.num("nozzle_target_temper")?.takeIf { it > 0 }?.let(::celsius)))
        }
        val bed = ((dev?.get("bed") as? JsonObject)?.get("info") as? JsonObject)?.int("temp")?.let(::unpack)
            ?: p.num("bed_temper")?.let(::celsius)?.let { HeaterReading(it, p.num("bed_target_temper")?.takeIf { t -> t > 0 }?.let(::celsius)) }
            ?: return null
        val chamber = ((dev?.get("ctc") as? JsonObject)?.get("info") as? JsonObject)?.int("temp")?.let(::unpack)
            ?: p.num("chamber_temper")?.let(::celsius)?.let { HeaterReading(it, null) }
        return CapabilityState.PrinterThermalsState(nozzles, bed, chamber)
    }

    /** Packed temperature word: low 16 bits current °C, high 16 bits target °C (0 = no target). */
    internal fun unpack(v: Long): HeaterReading? {
        if (v < 0 || v > 0xFFFF_FFFFL) return null
        val cur = celsius((v and 0xFFFF).toDouble()) ?: return null
        val tgt = ((v shr 16) and 0xFFFF).toDouble().takeIf { it > 0 }?.let(::celsius)
        return HeaterReading(cur, tgt)
    }

    private fun celsius(v: Double): Celsius? = v.takeIf { it.isFinite() && it in -40.0..400.0 }?.let(::Celsius)

    private fun slots(p: JsonObject): CapabilityState.FilamentSlotsState? {
        val ams = p["ams"] as? JsonObject ?: return null
        val now = (ams["tray_now"] as? JsonPrimitive)?.content?.toIntOrNull()
        val units = (ams["ams"] as? JsonArray).orEmpty().take(8)
        val out = ArrayList<FilamentSlot>()
        units.forEachIndexed { ui, u ->
            val unit = (u as? JsonObject) ?: return@forEachIndexed
            val unitId = (unit["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: ui
            (unit["tray"] as? JsonArray).orEmpty().take(4).forEachIndexed { ti, t ->
                val tray = t as? JsonObject ?: return@forEachIndexed
                val idx = (tray["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: ti
                val material = tray.str("tray_type")?.takeIf { it.isNotBlank() }?.take(16)
                val colour = tray.str("tray_color")?.takeIf { it.length == 8 && it.all { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' } }
                    ?.let { Rgb(it.substring(0, 2).toInt(16), it.substring(2, 4).toInt(16), it.substring(4, 6).toInt(16)) }
                val remain = tray.int("remain")?.takeIf { it in 0..100 }?.let { Percent(it.toDouble()) }
                out += FilamentSlot(unitId + 1, idx + 1, material, if (material != null) colour else null, if (material != null) remain else null, active = now == unitId * 4 + idx)
            }
        }
        return if (out.isEmpty()) null else CapabilityState.FilamentSlotsState(out)
    }

    private fun light(p: JsonObject): CapabilityState.AuxLightState? {
        val node = (p["lights_report"] as? JsonArray).orEmpty().firstOrNull { (it as? JsonObject)?.str("node") == "chamber_light" } as? JsonObject
            ?: return null
        return CapabilityState.AuxLightState("Chamber light", node.str("mode") == "on")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= 256 }
    private fun JsonObject.int(k: String): Long? = (this[k] as? JsonPrimitive)?.let { if (it.isString) it.content.toLongOrNull() else it.longOrNull }
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { if (it.isString) it.content.toDoubleOrNull() else it.doubleOrNull }

    private fun depth(e: JsonElement, d: Int = 0): Int = when {
        d > MAX_DEPTH -> d
        e is JsonObject -> (e.values.maxOfOrNull { depth(it, d + 1) } ?: d + 1)
        e is JsonArray -> (e.maxOfOrNull { depth(it, d + 1) } ?: d + 1)
        else -> d
    }

    private fun bracketDepthWithin(bytes: ByteArray, max: Int): Boolean {
        var depth = 0; var inString = false; var escaped = false
        for (b in bytes) {
            val ch = b.toInt().toChar()
            if (inString) {
                when { escaped -> escaped = false; ch == '\\' -> escaped = true; ch == '"' -> inString = false }
                continue
            }
            when (ch) { '"' -> inString = true; '{', '[' -> if (++depth > max) return false; '}', ']' -> depth-- }
        }
        return true
    }

    private fun bad(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("bambu: $why"))
}
