package dev.patchbay.core.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuietHoursTest {
    @Test
    fun aWindowThatCrossesMidnight() {
        val q = QuietHours(22 * 60, 7 * 60)
        assertTrue(q.contains(23 * 60))
        assertTrue(q.contains(0))
        assertTrue(q.contains(6 * 60 + 59))
        assertFalse(q.contains(7 * 60))
        assertFalse(q.contains(12 * 60))
        assertTrue(q.contains(22 * 60))
    }

    @Test
    fun aWindowWithinOneDay() {
        val q = QuietHours(13 * 60, 15 * 60)
        assertTrue(q.contains(13 * 60)); assertTrue(q.contains(14 * 60 + 59))
        assertFalse(q.contains(15 * 60)); assertFalse(q.contains(12 * 60))
    }

    @Test
    fun parsingAndTextRoundTrip() {
        assertEquals(QuietHours(22 * 60, 7 * 60), QuietHours.parse("22:00-07:00"))
        assertEquals("22:30-06:05", QuietHours(22 * 60 + 30, 6 * 60 + 5).text())
        assertEquals(QuietHours(22 * 60 + 30, 6 * 60 + 5), QuietHours.parse(QuietHours(22 * 60 + 30, 6 * 60 + 5).text()))
        for (bad in listOf("", "22:00", "25:00-07:00", "22:60-07:00", "22:00-22:00", "a-b", "22:00-07:00-08:00", "22-7")) assertNull(QuietHours.parse(bad), bad)
    }

    @Test
    fun rejectsNonsenseWindows() {
        assertFailsWith<IllegalArgumentException> { QuietHours(-1, 5) }
        assertFailsWith<IllegalArgumentException> { QuietHours(0, 1440) }
        assertFailsWith<IllegalArgumentException> { QuietHours(60, 60) }
    }
}
