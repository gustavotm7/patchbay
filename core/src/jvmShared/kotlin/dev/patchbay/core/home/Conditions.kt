package dev.patchbay.core.home

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Decides whether an automation's [AutomationCondition] holds at a given moment. Pure: the clock, zone, home location and away state are passed in. */
internal object Conditions {
    sealed interface Verdict {
        data object Holds : Verdict
        /** [why] reads after "Not run: it": "only runs after dark". */
        data class Fails(val why: String) : Verdict
    }

    fun check(c: AutomationCondition?, nowMillis: Long, zone: ZoneId, location: HomeLocation?, away: Boolean): Verdict {
        if (c == null || c.isEmpty) return Verdict.Holds
        c.whileAway?.let { wanted ->
            if (away != wanted) return Verdict.Fails(if (wanted) "only runs while you are away" else "only runs when you are at home")
        }
        c.window?.let { w ->
            val inside = inside(w, nowMillis, zone, location) ?: return Verdict.Fails("needs your home location or today's sun times to tell whether it is ${w.describe()}")
            if (!inside) return Verdict.Fails("only runs ${w.describe()}")
        }
        return Verdict.Holds
    }

    /** True or false, or null when a sun-based end cannot be worked out (no home location, or no sunrise or sunset that day near the poles). */
    fun inside(w: TimeWindow, nowMillis: Long, zone: ZoneId, location: HomeLocation?): Boolean? {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val from = instant(w.from, today, zone, location) ?: return null
        val until = instant(w.until, today, zone, location) ?: return null
        // A window that ends before it starts wraps past midnight: it is the evening part (today's start onward) or the morning part (before today's end).
        return if (from <= until) nowMillis in from until until else nowMillis >= from || nowMillis < until
    }

    /** The moment [p] falls on [day], or null for a sun-based point without a location or a sunrise or sunset. */
    private fun instant(p: DayPoint, day: LocalDate, zone: ZoneId, location: HomeLocation?): Long? = when (p) {
        is DayPoint.Clock -> day.atTime(p.hour, p.minute).atZone(zone).toInstant().toEpochMilli()
        is DayPoint.Sun -> {
            val loc = location
            val times = if (loc == null) null else SunTimes.compute(loc.latitude, loc.longitude, day.dayOfYear)
            times?.let { (rise, set) ->
                val minutes = (if (p.sunset) set else rise) + p.offsetMinutes
                // Sun times are minutes after 00:00 UTC of the day, whichever side of it they land in the owner's zone.
                day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + (minutes * 60_000).toLong()
            }
        }
    }

    fun toStored(c: AutomationCondition): StoredCondition = StoredCondition(c.window?.from?.text(), c.window?.until?.text(), c.whileAway)

    /** True when a window was stored but cannot be read back (one end missing or unreadable): such an automation must not run as if it had no condition. */
    fun isDamaged(s: StoredCondition?): Boolean {
        if (s == null || (s.from == null && s.until == null)) return false
        return s.from?.let(DayPoint::parse) == null || s.until?.let(DayPoint::parse) == null
    }

    /** Null when nothing is set; a damaged window (one end missing or unreadable) is dropped, keeping the away flag. */
    fun fromStored(s: StoredCondition?): AutomationCondition? {
        if (s == null) return null
        val from = s.from?.let(DayPoint::parse)
        val until = s.until?.let(DayPoint::parse)
        val window = if (from != null && until != null) TimeWindow(from, until) else null
        return AutomationCondition(window, s.away).takeUnless { it.isEmpty }
    }
}
