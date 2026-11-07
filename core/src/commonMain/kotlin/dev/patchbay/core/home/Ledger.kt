package dev.patchbay.core.home

/**
 * One line of the activity history: when, which device, what happened. Kept on this device only (encrypted with everything
 * else), bounded in size, and exported only when the user asks (as CSV text they copy).
 */
public data class LedgerEntry(val atMillis: Long, val deviceName: String, val event: String)

public object LedgerCsv {
    /** `time_utc,device,event` with ISO-8601 times; fields are quoted and quotes doubled. Newest first, as shown. */
    public fun of(entries: List<LedgerEntry>, isoTime: (Long) -> String): String = buildString {
        append("time_utc,device,event\n")
        for (e in entries) {
            append(isoTime(e.atMillis)).append(',').append(quote(e.deviceName)).append(',').append(quote(e.event)).append('\n')
        }
    }

    private fun quote(s: String): String {
        // Spreadsheet formula injection: a cell starting with = + - @ would be run by Excel. Prefix a quote.
        val safe = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
