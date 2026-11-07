package dev.patchbay.core.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveCurveTest {
    @Test
    fun hitsTheKeyPoints() {
        assertEquals(2200, AdaptiveCurve.kelvinAt(0))
        assertEquals(2700, AdaptiveCurve.kelvinAt(6 * 60))
        assertEquals(5500, AdaptiveCurve.kelvinAt(13 * 60))
        assertEquals(3000, AdaptiveCurve.kelvinAt(20 * 60))
        assertEquals(2200, AdaptiveCurve.kelvinAt(24 * 60))
    }

    @Test
    fun interpolatesAndStaysInRange() {
        assertEquals(2450, AdaptiveCurve.kelvinAt(180))
        assertEquals(5000, AdaptiveCurve.kelvinAt(11 * 60 + 30)) // between 4500 at 10:00 and 5500 at 13:00
        for (m in 0..1439) assertTrue(AdaptiveCurve.kelvinAt(m) in 2200..5500)
        // Rising through the morning, falling through the afternoon and evening.
        assertTrue(AdaptiveCurve.kelvinAt(9 * 60) < AdaptiveCurve.kelvinAt(12 * 60))
        assertTrue(AdaptiveCurve.kelvinAt(15 * 60) > AdaptiveCurve.kelvinAt(19 * 60))
        assertTrue(AdaptiveCurve.kelvinAt(19 * 60) > AdaptiveCurve.kelvinAt(21 * 60))
    }

    @Test
    fun outOfRangeMinutesAreClamped() {
        assertEquals(2200, AdaptiveCurve.kelvinAt(-5))
        assertEquals(2200, AdaptiveCurve.kelvinAt(5000))
    }
}
