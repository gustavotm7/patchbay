package dev.patchbay.core.home

/** A moment in the day: a clock time, or sunrise or sunset (shifted by up to three hours) from the owner's home location. */
public sealed interface DayPoint {
    public data class Clock(val hour: Int, val minute: Int) : DayPoint
    public data class Sun(val sunset: Boolean, val offsetMinutes: Int = 0) : DayPoint

    /** "07:30", "sunrise", "sunset+30", "sunrise-15": how a point is saved and typed. */
    public fun text(): String = when (this) {
        is Clock -> "%02d:%02d".format(hour, minute)
        is Sun -> (if (sunset) "sunset" else "sunrise") + if (offsetMinutes == 0) "" else "%+d".format(offsetMinutes)
    }

    public companion object {
        /** Reads what [text] writes; null for anything else, including out-of-range values. */
        public fun parse(text: String): DayPoint? {
            val t = text.trim().lowercase()
            Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(t)?.let { m ->
                val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
                return if (h in 0..23 && min in 0..59) Clock(h, min) else null
            }
            Regex("^(sunrise|sunset)([+-]\\d{1,3})?$").matchEntire(t)?.let { m ->
                val offset = m.groupValues[2].ifEmpty { "0" }.toInt()
                return if (offset in -180..180) Sun(m.groupValues[1] == "sunset", offset) else null
            }
            return null
        }
    }
}

/** A daily window from [from] up to (not including) [until]. It may wrap past midnight: from sunset until sunrise is the night. */
public data class TimeWindow(val from: DayPoint, val until: DayPoint) {
    /** True when an end depends on the owner's home location. */
    public val needsLocation: Boolean get() = from is DayPoint.Sun || until is DayPoint.Sun

    /** In words: "after dark", "during the day", "from sunset until 23:00". */
    public fun describe(): String = when (this) {
        AFTER_DARK -> "after dark"
        DAYTIME -> "during the day"
        else -> "from ${from.text()} until ${until.text()}"
    }

    public companion object {
        public val AFTER_DARK: TimeWindow = TimeWindow(DayPoint.Sun(sunset = true), DayPoint.Sun(sunset = false))
        public val DAYTIME: TimeWindow = TimeWindow(DayPoint.Sun(sunset = false), DayPoint.Sun(sunset = true))
    }
}

/**
 * When an automation may run: only inside [window], and only while away mode is [whileAway] (true: only away, false: only at home, null: either). What is set must all hold at the
 * moment the automation would run, otherwise it does nothing and Activity says why.
 */
public data class AutomationCondition(val window: TimeWindow? = null, val whileAway: Boolean? = null) {
    public val isEmpty: Boolean get() = window == null && whileAway == null

    public fun describe(): String = listOfNotNull(
        window?.describe(),
        whileAway?.let { if (it) "while away" else "when at home" },
    ).joinToString(", ").let { if (it.isEmpty()) "" else "only $it" }
}
