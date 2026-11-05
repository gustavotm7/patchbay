package dev.patchbay.ui.designsystem

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Locale-independent number formatting for readouts (common code has no String.format).
 * Uses a true minus sign (U+2212) so negative values line up with tabular figures.
 */
object Fmt {
    fun fixed(value: Double, decimals: Int): String {
        require(decimals in 0..4)
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = (abs(value) * factor).roundToLong()
        val whole = scaled / factor
        val frac = scaled % factor
        val sign = if (value < 0 && scaled != 0L) "−" else ""
        return if (decimals == 0) "$sign$whole" else "$sign$whole.${frac.toString().padStart(decimals, '0')}"
    }

    fun celsius(v: Double): String = fixed(v, 1)

    fun percent(v: Double): String = fixed(v, 0)

    /** "1 h 36 min", "8 min". */
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** "05:31" from milliseconds. */
    fun clock(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        fun two(x: Long) = x.toString().padStart(2, '0')
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}"
    }

    /** Wall-clock HH:mm in UTC offset [offsetMinutes] (the user's configured zone, Phase 1). */
    fun timeOfDay(epochMillis: Long, offsetMinutes: Int = 0): String {
        val minutesOfDay = ((epochMillis / 60_000 + offsetMinutes) % (24 * 60) + 24 * 60) % (24 * 60)
        return "${(minutesOfDay / 60).toString().padStart(2, '0')}:${(minutesOfDay % 60).toString().padStart(2, '0')}"
    }
}
