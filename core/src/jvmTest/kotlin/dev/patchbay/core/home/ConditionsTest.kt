package dev.patchbay.core.home

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConditionsTest {
    private val utc = ZoneId.of("UTC")
    private val london = HomeLocation(51.5, -0.12)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = utc): Long = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private fun holds(c: AutomationCondition?, now: Long, zone: ZoneId = utc, loc: HomeLocation? = london, away: Boolean = false) =
        Conditions.check(c, now, zone, loc, away) == Conditions.Verdict.Holds

    private fun clock(from: String, until: String) = TimeWindow(DayPoint.parse(from)!!, DayPoint.parse(until)!!)

    @Test
    fun noConditionAlwaysHolds() {
        assertTrue(holds(null, 0))
        assertTrue(holds(AutomationCondition(), 0))
    }

    @Test
    fun aClockWindowIncludesItsStartAndExcludesItsEnd() {
        val c = AutomationCondition(clock("09:00", "17:00"))
        assertTrue(!holds(c, at(2026, 11, 2, 8, 59)))
        assertTrue(holds(c, at(2026, 11, 2, 9, 0)))
        assertTrue(holds(c, at(2026, 11, 2, 16, 59)))
        assertTrue(!holds(c, at(2026, 11, 2, 17, 0)))
    }

    @Test
    fun aWindowThatEndsBeforeItStartsWrapsPastMidnight() {
        val c = AutomationCondition(clock("22:00", "06:00"))
        assertTrue(holds(c, at(2026, 11, 2, 23, 0)))
        assertTrue(holds(c, at(2026, 11, 2, 3, 0)))
        assertTrue(holds(c, at(2026, 11, 2, 22, 0)))
        assertTrue(!holds(c, at(2026, 11, 2, 6, 0)))
        assertTrue(!holds(c, at(2026, 11, 2, 12, 0)))
    }

    @Test
    fun clockTimesAreInTheOwnersZoneIncludingSummerTime() {
        val zone = ZoneId.of("Europe/London")
        val c = AutomationCondition(clock("23:00", "01:00"))
        // 22:30 UTC on 1 July is 23:30 in London (BST); 22:30 UTC on 1 December is 22:30 in London.
        assertTrue(holds(c, at(2026, 7, 1, 22, 30), zone))
        assertTrue(!holds(c, at(2026, 12, 1, 22, 30), zone))
    }

    @Test
    fun afterDarkFollowsTheSunAtTheHomeLocation() {
        val c = AutomationCondition(TimeWindow.AFTER_DARK)
        // London on 2 November: sunrise a little before 07:00 and sunset a little before 16:30 (UTC).
        assertTrue(holds(c, at(2026, 11, 2, 3, 0)))
        assertTrue(holds(c, at(2026, 11, 2, 6, 0)))
        assertTrue(!holds(c, at(2026, 11, 2, 8, 0)))
        assertTrue(!holds(c, at(2026, 11, 2, 12, 0)))
        assertTrue(!holds(c, at(2026, 11, 2, 15, 0)))
        assertTrue(holds(c, at(2026, 11, 2, 17, 30)))
        assertTrue(holds(c, at(2026, 11, 2, 23, 0)))
    }

    @Test
    fun daytimeIsTheOppositeOfAfterDark() {
        val day = AutomationCondition(TimeWindow.DAYTIME)
        val dark = AutomationCondition(TimeWindow.AFTER_DARK)
        for (h in 0..23) for (m in listOf(0, 30)) {
            val now = at(2026, 11, 2, h, m)
            assertTrue(holds(day, now) != holds(dark, now), "$h:$m")
        }
    }

    @Test
    fun sunEndsCanBeShifted() {
        // From an hour before sunset until 23:00.
        val c = AutomationCondition(TimeWindow(DayPoint.parse("sunset-60")!!, DayPoint.parse("23:00")!!))
        assertTrue(!holds(c, at(2026, 11, 2, 14, 30)))
        assertTrue(holds(c, at(2026, 11, 2, 15, 45))) // sunset is about 16:25, so the window opens about 15:25
        assertTrue(holds(c, at(2026, 11, 2, 22, 59)))
        assertTrue(!holds(c, at(2026, 11, 2, 23, 1)))
    }

    @Test
    fun aSunWindowWithoutALocationOrWithNoSunriseSaysSoInsteadOfGuessing() {
        val dark = AutomationCondition(TimeWindow.AFTER_DARK)
        val noLoc = Conditions.check(dark, at(2026, 11, 2, 12, 0), utc, null, false)
        assertIs<Conditions.Verdict.Fails>(noLoc)
        assertTrue("home location" in noLoc.why, noLoc.why)
        // Midsummer well inside the Arctic circle: the sun does not set, so there is no sunset to work from.
        val polar = Conditions.check(dark, at(2026, 6, 21, 12, 0), utc, HomeLocation(80.0, 10.0), false)
        assertIs<Conditions.Verdict.Fails>(polar)
        // A clock window never needs a location.
        assertTrue(holds(AutomationCondition(clock("09:00", "17:00")), at(2026, 11, 2, 12, 0), loc = null))
    }

    @Test
    fun theAwayFlagMustMatchAndTheReasonSaysWhat() {
        val home = AutomationCondition(whileAway = false)
        val away = AutomationCondition(whileAway = true)
        assertTrue(holds(home, 0, away = false)); assertTrue(!holds(home, 0, away = true))
        assertTrue(holds(away, 0, away = true)); assertTrue(!holds(away, 0, away = false))
        assertEquals("only runs when you are at home", (Conditions.check(home, 0, utc, null, true) as Conditions.Verdict.Fails).why)
        assertEquals("only runs while you are away", (Conditions.check(away, 0, utc, null, false) as Conditions.Verdict.Fails).why)
    }

    @Test
    fun everythingSetMustHoldAndTheFirstFailureIsReported() {
        val c = AutomationCondition(TimeWindow.AFTER_DARK, whileAway = false)
        assertTrue(holds(c, at(2026, 11, 2, 22, 0), away = false))
        assertTrue(!holds(c, at(2026, 11, 2, 22, 0), away = true))
        assertEquals("only runs after dark", (Conditions.check(c, at(2026, 11, 2, 12, 0), utc, london, false) as Conditions.Verdict.Fails).why)
    }

    @Test
    fun describeReadsNaturally() {
        assertEquals("only after dark", AutomationCondition(TimeWindow.AFTER_DARK).describe())
        assertEquals("only during the day, when at home", AutomationCondition(TimeWindow.DAYTIME, false).describe())
        assertEquals("only from sunset until 23:00", AutomationCondition(TimeWindow(DayPoint.parse("sunset")!!, DayPoint.parse("23:00")!!)).describe())
        assertEquals("only while away", AutomationCondition(whileAway = true).describe())
        assertEquals("", AutomationCondition().describe())
    }

    @Test
    fun dayPointsReadAndWriteTheSameText() {
        for (t in listOf("07:30", "00:00", "23:59", "sunrise", "sunset", "sunset+30", "sunrise-15", "sunset+180", "sunrise-180")) assertEquals(t, DayPoint.parse(t)!!.text(), t)
        assertEquals(DayPoint.Clock(7, 5), DayPoint.parse("7:05"))
        assertEquals(DayPoint.Sun(true, 0), DayPoint.parse(" SUNSET "))
        for (bad in listOf("", "24:00", "12:60", "7:5", "sunset+181", "sunset 30", "midnight", "12", "sun", "-5", "99:99", "sunrise+", "12:30:00")) assertNull(DayPoint.parse(bad), bad)
    }

    @Test
    fun readingArbitraryTextNeverThrows() {
        val rnd = Random(77)
        val alphabet = "0123456789:+-sunriseset \u0000x"
        repeat(20_000) { DayPoint.parse(String(CharArray(rnd.nextInt(0, 14)) { alphabet[rnd.nextInt(alphabet.length)] })) }
    }

    @Test
    fun savedFormRoundTripsAndADamagedWindowKeepsTheAwayFlag() {
        val c = AutomationCondition(TimeWindow(DayPoint.parse("sunset+15")!!, DayPoint.parse("23:30")!!), whileAway = true)
        assertEquals(c, Conditions.fromStored(Conditions.toStored(c)))
        assertNull(Conditions.fromStored(null))
        assertNull(Conditions.fromStored(StoredCondition()))
        assertEquals(AutomationCondition(whileAway = false), Conditions.fromStored(StoredCondition(from = "garbage", until = "23:00", away = false)))
        assertNull(Conditions.fromStored(StoredCondition(from = "sunset", until = null, away = null)))
    }
}
