package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId

/**
 * Adaptive lighting: the white colour of a light follows the day, cool at midday and warm in the evening. Only the colour
 * temperature moves; brightness and colour mode are never touched. Piecewise-linear between fixed points.
 */
public object AdaptiveCurve {
    private val points = listOf(0 to 2200, 6 * 60 to 2700, 10 * 60 to 4500, 13 * 60 to 5500, 17 * 60 to 4500, 20 * 60 to 3000, 22 * 60 to 2400, 24 * 60 to 2200)

    /** The target colour temperature in kelvin for a minute of the day (0 to 1439). */
    public fun kelvinAt(minuteOfDay: Int): Int {
        val m = minuteOfDay.coerceIn(0, 24 * 60)
        for (i in 1 until points.size) {
            val (m1, k1) = points[i]
            if (m <= m1) {
                val (m0, k0) = points[i - 1]
                return k0 + (k1 - k0) * (m - m0) / (m1 - m0)
            }
        }
        return points.last().second
    }
}

/**
 * Away mode: on turning it on, everything switches off. Optionally it imitates someone being home: the chosen lights switch on and
 * off at irregular times between 18:00 and 23:00, and go off at 23:00. Runs only while Patchbay is open or in the tray.
 */
public data class AwayState(val active: Boolean = false, val simulate: Set<DeviceId> = emptySet())
