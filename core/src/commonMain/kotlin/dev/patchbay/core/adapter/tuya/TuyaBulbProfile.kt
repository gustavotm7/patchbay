package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An explicit allowlist of the data points a Tuya bulb may be sent or read (ADR 0007): power, mode, brightness, colour
 * temperature and colour. Anything else the device reports is ignored; nothing else is ever written.
 *
 * Two layouts exist in the wild. [V2] is the current standard instruction set (data points 20 to 24); [V1] is the older
 * one (1 to 5). Brightness and temperature ranges differ. The Kelvin range is an assumption (most white-tunable bulbs
 * are about 2700 to 6500 K) that hardware testing should calibrate.
 */
public class TuyaBulbProfile private constructor(
    public val id: String,
    public val label: String,
    private val power: String,
    private val mode: String,
    private val bright: String,
    private val temp: String,
    private val colour: String,
    private val brightRange: IntRange,
    private val tempMax: Int,
    private val colourIsV2: Boolean,
    public val warmest: Int = 2_700,
    public val coolest: Int = 6_500,
) {
    public val powerDp: String get() = power

    public val capabilities: List<Capability> = listOf(
        Capability.Power, Capability.Brightness(), Capability.ColourTemperature(Kelvin(warmest), Kelvin(coolest)), Capability.RgbColour,
    )

    /** Translates a command into the data points to write. Returns Unsupported for anything outside the allowlist. */
    public fun toDps(command: Command): PatchbayResult<Map<String, JsonPrimitive>> = when (command) {
        is Command.SetPower -> ok(power to JsonPrimitive(command.on))
        is Command.SetBrightness -> {
            val p = command.level.value.roundToInt().coerceIn(1, 100)
            ok(bright to JsonPrimitive(brightRange.first + ((p - 1) / 99.0 * (brightRange.last - brightRange.first)).roundToInt()))
        }
        is Command.SetColourTemperature -> {
            val k = command.kelvin.value.coerceIn(warmest, coolest)
            val t = ((k - warmest).toDouble() / (coolest - warmest) * tempMax).roundToInt()
            ok(mode to JsonPrimitive("white"), temp to JsonPrimitive(t))
        }
        is Command.SetColour -> ok(mode to JsonPrimitive("colour"), colour to JsonPrimitive(colourString(command.colour)))
        else -> PatchbayResult.Err(PatchbayError.Unsupported("Tuya bulbs don't support ${command::class.simpleName}"))
    }

    /** Reads the allowlisted data points into capability states. */
    public fun states(dps: Map<String, JsonPrimitive>): List<CapabilityState> {
        val out = ArrayList<CapabilityState>()
        dps[power]?.booleanOrNull?.let { out += CapabilityState.PowerState(it) }
        dps[bright]?.intOrNull?.takeIf { it in brightRange }?.let {
            val p = 1 + ((it - brightRange.first).toDouble() / (brightRange.last - brightRange.first) * 99).roundToInt()
            out += CapabilityState.BrightnessState(Percent(min(100, max(1, p)).toDouble()))
        }
        when (dps[mode]?.takeIf { it.isString }?.content) {
            "white" -> dps[temp]?.intOrNull?.takeIf { it in 0..tempMax }?.let {
                out += CapabilityState.ColourTemperatureState(Kelvin(warmest + (it.toDouble() / tempMax * (coolest - warmest)).roundToInt()))
            }
            "colour" -> dps[colour]?.takeIf { it.isString }?.let { c -> parseColour(c.content)?.let { out += CapabilityState.RgbState(it) } }
        }
        return out
    }

    private fun ok(vararg pairs: Pair<String, JsonPrimitive>): PatchbayResult<Map<String, JsonPrimitive>> = PatchbayResult.Ok(linkedMapOf(*pairs))

    // v2: HHHHSSSSVVVV (hue 0..360, saturation and value 0..1000). v1: RRGGBB then HHHH SS VV (saturation and value 0..255).
    private fun colourString(rgb: Rgb): String {
        val (h, s, v) = hsv(rgb)
        return if (colourIsV2) hex(h.roundToInt(), 4) + hex((s * 1000).roundToInt(), 4) + hex((v * 1000).roundToInt(), 4)
        else hex(rgb.r, 2) + hex(rgb.g, 2) + hex(rgb.b, 2) + hex(h.roundToInt(), 4) + hex((s * 255).roundToInt(), 2) + hex((v * 255).roundToInt(), 2)
    }

    private fun parseColour(s: String): Rgb? {
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return if (colourIsV2) {
            if (s.length != 12) return null
            val h = s.substring(0, 4).toInt(16); val sat = s.substring(4, 8).toInt(16); val v = s.substring(8, 12).toInt(16)
            if (h > 360 || sat > 1000 || v > 1000) null else fromHsv(h.toDouble(), sat / 1000.0, v / 1000.0)
        } else {
            if (s.length != 14) return null
            Rgb(s.substring(0, 2).toInt(16), s.substring(2, 4).toInt(16), s.substring(4, 6).toInt(16))
        }
    }

    private fun hex(v: Int, width: Int): String = v.coerceAtLeast(0).toString(16).padStart(width, '0')

    private fun hsv(c: Rgb): Triple<Double, Double, Double> {
        val r = c.r / 255.0; val g = c.g / 255.0; val b = c.b / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0.0 -> 0.0
            mx == r -> 60 * (((g - b) / d) % 6)
            mx == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return Triple(h, if (mx == 0.0) 0.0 else d / mx, mx)
    }

    private fun fromHsv(h: Double, s: Double, v: Double): Rgb {
        val c = v * s; val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1)); val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0); h < 120 -> Triple(x, c, 0.0); h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c); h < 300 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun ch(f: Double) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
        return Rgb(ch(r), ch(g), ch(b))
    }

    public companion object {
        public val V2: TuyaBulbProfile = TuyaBulbProfile("bulb-v2", "Bulb (standard)", "20", "21", "22", "23", "24", 10..1000, 1000, colourIsV2 = true)
        public val V1: TuyaBulbProfile = TuyaBulbProfile("bulb-v1", "Bulb (older)", "1", "2", "3", "4", "5", 25..255, 255, colourIsV2 = false)
        public val ALL: List<TuyaBulbProfile> = listOf(V2, V1)
        public fun byId(id: String): TuyaBulbProfile? = ALL.firstOrNull { it.id == id }
        public fun byLabel(label: String?): TuyaBulbProfile? = ALL.firstOrNull { it.label == label }
    }
}
