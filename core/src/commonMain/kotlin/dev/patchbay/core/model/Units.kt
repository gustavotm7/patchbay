package dev.patchbay.core.model

import kotlin.jvm.JvmInline
import kotlin.math.round

/*
 * SI/metric value types. Everything inside core and the UI uses these; adapters convert at the
 * boundary (e.g. a device speaking °F converts with [Celsius.fromFahrenheit]).
 * Constructors validate, so a malformed device value cannot travel further than the parser.
 */

/** Temperature in degrees Celsius. */
@JvmInline
public value class Celsius(public val value: Double) : Comparable<Celsius> {
    init {
        require(value.isFinite() && value in MIN..MAX) { "temperature out of range: $value °C" }
    }

    override fun compareTo(other: Celsius): Int = value.compareTo(other.value)

    public operator fun plus(delta: Double): Celsius = Celsius(value + delta)
    public operator fun minus(other: Celsius): Double = value - other.value

    /** Rounds to the nearest multiple of [step] (for example 0.5 °C thermostat steps). */
    public fun roundTo(step: Double): Celsius = Celsius(round(value / step) * step)

    public companion object {
        // Wide enough for printer hot-ends (≤ 350 °C on H2D) and outdoor sensors.
        public const val MIN: Double = -90.0
        public const val MAX: Double = 500.0

        public fun fromFahrenheit(f: Double): Celsius = Celsius((f - 32.0) * 5.0 / 9.0)
    }
}

/** A ratio in percent, 0–100 inclusive. */
@JvmInline
public value class Percent(public val value: Double) : Comparable<Percent> {
    init {
        require(value.isFinite() && value in 0.0..100.0) { "percent out of range: $value" }
    }

    override fun compareTo(other: Percent): Int = value.compareTo(other.value)

    public val fraction: Double get() = value / 100.0

    public companion object {
        public fun ofFraction(f: Double): Percent = Percent((f * 100.0).coerceIn(0.0, 100.0))
    }
}

/** Correlated colour temperature in kelvin. */
@JvmInline
public value class Kelvin(public val value: Int) : Comparable<Kelvin> {
    init {
        require(value in 1_000..20_000) { "colour temperature out of range: $value K" }
    }

    override fun compareTo(other: Kelvin): Int = value.compareTo(other.value)
}

/** Length in millimetres (used for printer layer heights and floor-plan geometry in mm). */
@JvmInline
public value class Millimetre(public val value: Double) : Comparable<Millimetre> {
    init {
        require(value.isFinite()) { "length must be finite" }
    }

    override fun compareTo(other: Millimetre): Int = value.compareTo(other.value)
}

/** An sRGB colour as emitted by a device (a light's real output, a filament swatch). */
public data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "rgb component out of range" }
    }

    public val packed: Int get() = (r shl 16) or (g shl 8) or b

    public companion object {
        public fun ofPacked(argbOrRgb: Int): Rgb =
            Rgb((argbOrRgb shr 16) and 0xFF, (argbOrRgb shr 8) and 0xFF, argbOrRgb and 0xFF)
    }
}

/** Wall-clock instant as milliseconds since the Unix epoch (UTC). */
@JvmInline
public value class EpochMillis(public val value: Long) : Comparable<EpochMillis> {
    override fun compareTo(other: EpochMillis): Int = value.compareTo(other.value)
}
