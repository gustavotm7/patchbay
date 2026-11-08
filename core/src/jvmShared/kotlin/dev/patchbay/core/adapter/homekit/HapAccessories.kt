package dev.patchbay.core.adapter.homekit

import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Celsius
import dev.patchbay.core.model.HvacAction
import dev.patchbay.core.model.HvacMode
import dev.patchbay.core.model.Percent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One characteristic from `GET /accessories`. [type] is the 32-bit short UUID for Apple-defined types, or -1 for anything else. */
internal data class HapChar(
    val aid: Int, val iid: Int, val type: Int, val perms: Set<String>,
    val min: Double?, val max: Double?, val step: Double?, val validValues: Set<Int>?, val value: JsonElement?,
) {
    val id: String get() = "$aid.$iid"
    val writable: Boolean get() = "pw" in perms
}

internal data class HapService(val type: Int, val chars: List<HapChar>) {
    fun char(type: Int): HapChar? = chars.firstOrNull { it.type == type }
}

internal data class HapAccessory(val aid: Int, val services: List<HapService>)

internal object HapDb {
    // Apple-defined service and characteristic short UUIDs (the HAP specification's tables).
    const val SVC_THERMOSTAT = 0x4A; const val SVC_HUMIDITY = 0x82; const val SVC_OCCUPANCY = 0x86
    const val CH_CURRENT_STATE = 0x0F; const val CH_TARGET_STATE = 0x33; const val CH_CURRENT_TEMP = 0x11; const val CH_TARGET_TEMP = 0x35
    const val CH_COOL_THRESHOLD = 0x0D; const val CH_HEAT_THRESHOLD = 0x12; const val CH_HUMIDITY = 0x10; const val CH_OCCUPANCY = 0x71; const val CH_NAME = 0x23

    private const val BASE_SUFFIX = "-0000-1000-8000-0026BB765291"
    private const val MAX_ACCESSORIES = 64
    private const val MAX_SERVICES = 48
    private const val MAX_CHARS = 64

    private val json = Json { isLenient = false }

    /** "4A", "0000004A-0000-1000-8000-0026BB765291" → 0x4A; any other UUID → -1. */
    fun shortUuid(raw: String): Int {
        val u = raw.trim().uppercase()
        if (u.length > 36) return -1
        val head = if (u.endsWith(BASE_SUFFIX)) u.removeSuffix(BASE_SUFFIX) else if ('-' !in u) u else return -1
        if (head.isEmpty() || head.length > 8 || !head.all { it in '0'..'9' || it in 'A'..'F' }) return -1
        return head.toLong(16).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: -1
    }

    fun parseAccessories(body: ByteArray): List<HapAccessory>? {
        val root = try { json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null } ?: return null
        val list = (root["accessories"] as? JsonArray)?.takeIf { it.size <= MAX_ACCESSORIES } ?: return null
        return list.map { a ->
            val ao = a as? JsonObject ?: return null
            val aid = ao.int("aid") ?: return null
            val services = (ao["services"] as? JsonArray)?.takeIf { it.size <= MAX_SERVICES } ?: return null
            HapAccessory(
                aid,
                services.map { s ->
                    val so = s as? JsonObject ?: return null
                    val chars = (so["characteristics"] as? JsonArray)?.takeIf { it.size <= MAX_CHARS } ?: return null
                    HapService(
                        shortUuid(so.str("type") ?: return null),
                        chars.map { c ->
                            val co = c as? JsonObject ?: return null
                            HapChar(
                                aid, co.int("iid") ?: return null, shortUuid(co.str("type") ?: return null),
                                (co["perms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty(),
                                co.dbl("minValue"), co.dbl("maxValue"), co.dbl("minStep"),
                                (co["valid-values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet(),
                                co["value"],
                            )
                        },
                    )
                },
            )
        }
    }

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun asDouble(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.booleanOrNull?.let { b -> if (b) 1.0 else 0.0 } }?.takeIf { it.isFinite() }

    fun readUrl(chars: List<HapChar>): String = "/characteristics?id=" + chars.joinToString(",") { it.id }

    /** Parses a `GET /characteristics` reply into id → value, leaving out any characteristic the accessory reported an error for. */
    fun parseValues(body: ByteArray): Map<String, JsonElement>? {
        val root = try { json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null } ?: return null
        val list = (root["characteristics"] as? JsonArray)?.takeIf { it.size <= 256 } ?: return null
        val out = HashMap<String, JsonElement>()
        for (c in list) {
            val o = c as? JsonObject ?: return null
            val status = o.int("status")
            if (status != null && status != 0) continue
            val value = o["value"] ?: continue
            out["${o.int("aid") ?: return null}.${o.int("iid") ?: return null}"] = value
        }
        return out
    }

    fun writeBody(writes: List<Pair<HapChar, JsonElement>>): ByteArray = buildJsonObject {
        put("characteristics", buildJsonArray {
            for ((c, v) in writes) add(buildJsonObject { put("aid", c.aid); put("iid", c.iid); put("value", v) })
        })
    }.toString().toByteArray()

    /** True when a PUT answered 204, or 207 with every status 0. */
    fun writeSucceeded(status: Int, body: ByteArray): Boolean {
        if (status == 204) return true
        if (status != 207) return false
        val root = try { json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject } catch (e: Exception) { null } ?: return false
        val list = root["characteristics"] as? JsonArray ?: return false
        return list.all { ((it as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull == 0 }
    }
}

/** The thermostat (and the sensors that come with it) found in an accessory database, and how to turn readings into model state. */
internal class ThermostatLayout private constructor(
    val name: String?,
    val currentTemp: HapChar, val targetTemp: HapChar, val currentState: HapChar, val targetState: HapChar,
    val coolThreshold: HapChar?, val heatThreshold: HapChar?, val humidity: HapChar?,
    val occupancy: List<Pair<String, HapChar>>,
) {
    val readIds: List<HapChar> = listOfNotNull(currentTemp, targetTemp, currentState, targetState, coolThreshold, heatThreshold, humidity) + occupancy.map { it.second }

    val modes: Set<HvacMode> = run {
        val valid = targetState.validValues ?: (((targetState.min ?: 0.0).toInt())..((targetState.max ?: 3.0).toInt())).toSet()
        valid.mapNotNull(::modeOf).toSet().ifEmpty { setOf(HvacMode.OFF) }
    }

    fun capabilities(): List<Capability> {
        fun range(c: HapChar?, fallback: ClosedFloatingPointRange<Double>) = Celsius((c?.min ?: fallback.start)).rangeTo(Celsius(c?.max ?: fallback.endInclusive))
        val tMin = targetTemp.min ?: 10.0; val tMax = targetTemp.max ?: 38.0
        val caps = mutableListOf<Capability>(
            Capability.Thermostat(
                modes = modes,
                heatRange = range(heatThreshold, tMin..tMax).let { if (heatThreshold == null) range(targetTemp, tMin..tMax) else it },
                coolRange = range(coolThreshold, tMin..tMax).let { if (coolThreshold == null) range(targetTemp, tMin..tMax) else it },
                minimumDeadband = 1.0,
            ),
        )
        if (humidity != null) caps += Capability.HumiditySensor
        caps += occupancy.map { Capability.OccupancySensor(it.first) }
        return caps
    }

    /** Null when the readings we must have (temperature and modes) are missing or out of range; the caller treats that as an unusable reply. */
    fun states(v: Map<String, JsonElement>): List<CapabilityState>? {
        fun num(c: HapChar?) = c?.let { HapDb.asDouble(v[it.id]) }
        val current = num(currentTemp) ?: return null
        val target = num(targetTemp)
        val mode = num(targetState)?.toInt()?.let(::modeOf) ?: return null
        val action = when (num(currentState)?.toInt()) { 1 -> HvacAction.HEATING; 2 -> HvacAction.COOLING; else -> HvacAction.IDLE }
        fun c(x: Double?) = x?.takeIf { it in Celsius.MIN..Celsius.MAX }?.let { Celsius(it) }
        val heat: Celsius?; val cool: Celsius?
        when (mode) {
            HvacMode.HEAT -> { heat = c(target); cool = null }
            HvacMode.COOL -> { heat = null; cool = c(target) }
            HvacMode.AUTO -> { heat = c(num(heatThreshold)); cool = c(num(coolThreshold)) }
            HvacMode.OFF -> { heat = null; cool = null }
        }
        val out = mutableListOf<CapabilityState>(CapabilityState.ThermostatState(mode, action, c(current) ?: return null, heat, cool))
        num(humidity)?.takeIf { it in 0.0..100.0 }?.let { out += CapabilityState.HumidityReading(Percent(it)) }
        for ((name, ch) in occupancy) num(ch)?.let { out += CapabilityState.OccupancyReading(name, it != 0.0) }
        return out
    }

    companion object {
        fun modeOf(code: Int): HvacMode? = when (code) { 0 -> HvacMode.OFF; 1 -> HvacMode.HEAT; 2 -> HvacMode.COOL; 3 -> HvacMode.AUTO; else -> null }
        fun codeOf(mode: HvacMode): Int = when (mode) { HvacMode.OFF -> 0; HvacMode.HEAT -> 1; HvacMode.COOL -> 2; HvacMode.AUTO -> 3 }

        /** Finds the first complete thermostat service; sensors come from the thermostat's own accessory first, then the others. */
        fun find(accessories: List<HapAccessory>): ThermostatLayout? {
            for (acc in accessories) {
                val svc = acc.services.firstOrNull { it.type == HapDb.SVC_THERMOSTAT } ?: continue
                val ct = svc.char(HapDb.CH_CURRENT_TEMP) ?: continue
                val tt = svc.char(HapDb.CH_TARGET_TEMP) ?: continue
                val cs = svc.char(HapDb.CH_CURRENT_STATE) ?: continue
                val ts = svc.char(HapDb.CH_TARGET_STATE) ?: continue
                val all = listOf(acc) + accessories.filter { it !== acc }
                val hum = svc.char(HapDb.CH_HUMIDITY) ?: all.firstNotNullOfOrNull { a -> a.services.firstOrNull { it.type == HapDb.SVC_HUMIDITY }?.char(HapDb.CH_HUMIDITY) }
                val occ = all.flatMap { a -> a.services.filter { it.type == HapDb.SVC_OCCUPANCY } }.mapNotNull { s ->
                    val ch = s.char(HapDb.CH_OCCUPANCY) ?: return@mapNotNull null
                    val nm = (s.char(HapDb.CH_NAME)?.value as? JsonPrimitive)?.contentOrNull?.trim()?.take(32)?.takeIf { it.isNotEmpty() }
                    (nm ?: "Sensor") to ch
                }.take(8).let { list -> // names must be unique: they identify the state
                    val seen = HashMap<String, Int>()
                    list.map { (n, c) -> val k = seen.merge(n, 1, Int::plus)!!; (if (k > 1) "$n $k" else n) to c }
                }
                val name = (svc.char(HapDb.CH_NAME)?.value as? JsonPrimitive)?.contentOrNull?.trim()?.take(48)?.takeIf { it.isNotEmpty() }
                return ThermostatLayout(name, ct, tt, cs, ts, svc.char(HapDb.CH_COOL_THRESHOLD), svc.char(HapDb.CH_HEAT_THRESHOLD), hum, occ)
            }
            return null
        }
    }
}
