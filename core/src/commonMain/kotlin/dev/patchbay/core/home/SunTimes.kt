package dev.patchbay.core.home

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A home location typed by the owner. Kept on this device; used only to work out sunrise and sunset. Never read from the phone. */
public data class HomeLocation(val latitude: Double, val longitude: Double)

/**
 * Sunrise and sunset from the NOAA general solar position equations (good to a few minutes away from the poles).
 * Pure maths: no network, no device location.
 */
public object SunTimes {
    /** Sunrise and sunset as minutes after 00:00 UTC of the given day of year (may fall outside 0..1440). Null in polar day or night. */
    public fun compute(latitude: Double, longitude: Double, dayOfYear: Int): Pair<Double, Double>? {
        val rad = PI / 180.0
        val g = 2.0 * PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val cosHa = cos(90.833 * rad) / (cos(latitude * rad) * cos(decl)) - tan(latitude * rad) * tan(decl)
        if (cosHa < -1.0 || cosHa > 1.0) return null
        val ha = acos(cosHa) / rad
        return (720.0 - 4.0 * (longitude + ha) - eqTime) to (720.0 - 4.0 * (longitude - ha) - eqTime)
    }
}
