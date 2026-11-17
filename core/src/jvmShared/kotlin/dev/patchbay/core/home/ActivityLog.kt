package dev.patchbay.core.home

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What changed and when, newest first (bounded), kept on this device and saved in batches. */
internal class ActivityLog(private val ctx: HomeContext) {
    private val _entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val entries: StateFlow<List<LedgerEntry>> = _entries.asStateFlow()
    @Volatile private var flush: Job? = null

    fun load() {
        val raw = ctx.store?.setting("ledger") ?: return
        _entries.value = try {
            ctx.json.decodeFromString<List<StoredLedgerEntry>>(raw).map { LedgerEntry(it.t, it.d, it.e) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds an entry (newest first, at most 300) and schedules a write; writes are batched so a burst costs one. */
    fun record(deviceName: String, event: String) {
        _entries.update { (listOf(LedgerEntry(ctx.clock(), deviceName, event)) + it).take(300) }
        val store = ctx.store
        if (store != null && flush == null) {
            flush = ctx.scope.launch {
                delay(2_000)
                flush = null
                store.putSetting("ledger", ctx.json.encodeToString(_entries.value.map { StoredLedgerEntry(it.atMillis, it.deviceName, it.event) }))
            }
        }
    }

    fun csv(): String = LedgerCsv.of(_entries.value) { java.time.Instant.ofEpochMilli(it).toString() }

    fun timeLabel(epochMillis: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val t = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(ctx.clock()).atZone(zone).toLocalDate()
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) {
            today -> "Today $hhmm"
            today.minusDays(1) -> "Yesterday $hhmm"
            else -> "${t.dayOfMonth} ${t.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} $hhmm"
        }
    }
}
