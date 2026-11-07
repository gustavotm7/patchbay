package dev.patchbay.core.home

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SunTimesTest {
    private fun near(expected: Int, actual: Double, tol: Int = 5) = assertTrue(abs(expected - actual) <= tol, "expected about $expected but was $actual")

    @Test
    fun londonMidsummer() {
        // 21 June (day 172), London: sunrise about 03:43 UTC, sunset about 20:21 UTC.
        val (rise, set) = assertNotNull(SunTimes.compute(51.5074, -0.1278, 172))
        near(3 * 60 + 43, rise); near(20 * 60 + 21, set)
    }

    @Test
    fun londonMidwinter() {
        // 21 December (day 355): about 08:04 and 15:53 UTC.
        val (rise, set) = assertNotNull(SunTimes.compute(51.5074, -0.1278, 355))
        near(8 * 60 + 4, rise); near(15 * 60 + 53, set)
    }

    @Test
    fun equatorAtTheEquinox() {
        // Quito (0, -78.5), 20 March (day 79): about 06:17 and 18:23 local (UTC-5), i.e. 11:17 and 23:23 UTC.
        val (rise, set) = assertNotNull(SunTimes.compute(0.0, -78.5, 79))
        near(11 * 60 + 17, rise); near(23 * 60 + 23, set)
        assertTrue(set > 1_380) // west of Greenwich the UTC sunset runs into the next UTC day
    }

    @Test
    fun polarDayAndNightHaveNoSunrise() {
        assertNull(SunTimes.compute(78.2, 15.6, 172)) // Svalbard, midsummer
        assertNull(SunTimes.compute(78.2, 15.6, 355)) // Svalbard, midwinter
    }
}
