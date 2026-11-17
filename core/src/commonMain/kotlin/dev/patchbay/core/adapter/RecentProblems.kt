package dev.patchbay.core.adapter

/**
 * The last few things an adapter could not make sense of or could not do, kept for the diagnostics report. Entries are short, fixed-shape
 * reasons ("tuya: reply wasn't a protocol 3.3 message", "timeout"), never payloads or secrets, and are stripped of control characters and
 * cut to a fixed length here as a second line of defence. The same reason several times in a row is one entry with a count, so a device that
 * fails every few seconds cannot push everything else out. The newest entries are kept.
 */
public class RecentProblems(private val capacity: Int = 12) {
    private class Entry(val text: String, var count: Int)

    private val items = ArrayDeque<Entry>()

    @Synchronized
    public fun add(what: String) {
        val clean = what.filter { !it.isISOControl() }.take(MAX_LENGTH)
        if (clean.isEmpty()) return
        val last = items.lastOrNull()
        if (last != null && last.text == clean) {
            if (last.count < MAX_COUNT) last.count++
            return
        }
        if (items.size >= capacity) items.removeFirst()
        items.addLast(Entry(clean, 1))
    }

    @Synchronized
    public fun list(): List<String> = items.map { if (it.count > 1) "${it.text} (x${it.count})" else it.text }

    public companion object {
        public const val MAX_LENGTH: Int = 140
        public const val MAX_COUNT: Int = 9_999
    }
}
