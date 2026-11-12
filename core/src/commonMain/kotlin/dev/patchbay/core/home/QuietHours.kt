package dev.patchbay.core.home

/**
 * A daily window (minutes after midnight, local time) when alerts stay silent: no 3 a.m. "stopped answering". The window may cross
 * midnight (22:00 to 07:00). Only security alerts (a changed device key) ignore it. Everything is still written to Activity.
 */
public data class QuietHours(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439) { "minutes are 0 to 1439" }
        require(startMinute != endMinute) { "start and end must differ" }
    }

    public fun contains(minuteOfDay: Int): Boolean =
        if (startMinute < endMinute) minuteOfDay in startMinute until endMinute else minuteOfDay >= startMinute || minuteOfDay < endMinute

    public fun text(): String = "${hhmm(startMinute)}-${hhmm(endMinute)}"

    public companion object {
        private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

        /** Parses "22:00-07:00"; null when it isn't one. */
        public fun parse(text: String): QuietHours? {
            val parts = text.trim().split('-')
            if (parts.size != 2) return null
            fun minute(t: String): Int? {
                val hm = t.trim().split(':')
                if (hm.size != 2) return null
                val h = hm[0].toIntOrNull() ?: return null
                val m = hm[1].toIntOrNull() ?: return null
                return if (h in 0..23 && m in 0..59) h * 60 + m else null
            }
            val a = minute(parts[0]) ?: return null
            val b = minute(parts[1]) ?: return null
            return if (a == b) null else QuietHours(a, b)
        }
    }
}
