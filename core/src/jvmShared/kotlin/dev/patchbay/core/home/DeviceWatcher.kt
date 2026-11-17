package dev.patchbay.core.home

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.Metric
import dev.patchbay.core.model.PlaybackStatus
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.model.TrendSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches what each device reports and turns changes into what the owner should know: lines in Activity, notifications (a changed security key, a
 * device that stopped answering, a print that finished) and the history behind the little graphs.
 */
internal class DeviceWatcher(
    private val ctx: HomeContext,
    private val activity: ActivityLog,
    private val notifier: Notifier,
    /** Puts a problem on the banner and in the log. */
    private val report: (String) -> Unit,
) {
    private data class Summary(val reach: Reachability, val power: Boolean?, val stage: PrintStage?, val media: PlaybackStatus?)

    private val lastSummary = ConcurrentHashMap<DeviceId, Summary>()
    private val printStartedAt = ConcurrentHashMap<DeviceId, Long>()
    private val lastCommandAt = ConcurrentHashMap<DeviceId, Long>()
    private val trustAlerted = ConcurrentHashMap.newKeySet<DeviceId>()
    private val recorder = TrendRecorder()
    @Volatile private var trendsSavedAt = 0L

    val trends: TrendSource = TrendSource { device, metric -> recorder.trend(device, metric, ctx.clock()) }

    fun loadTrends() {
        ctx.store?.setting("trends")?.let { raw ->
            try { recorder.import(ctx.json.decodeFromString<List<StoredTrend>>(raw).map { TrendRecorder.Exported(it.d, it.m, it.t, it.v) }) } catch (e: Exception) { /* a damaged history is dropped, never fatal */ }
        }
    }

    /** Remembers when Patchbay itself sent a command, so a change right after it is described as ours. */
    fun commandSent(device: DeviceId) {
        lastCommandAt[device] = ctx.clock()
    }

    fun forget(device: DeviceId) {
        recorder.forget(device)
        lastSummary.remove(device); trustAlerted.remove(device)
    }

    /** Called after every report from a device, once the home's own list has been updated. [trust] is false when the device presented a different key. */
    fun onSnapshot(snap: DeviceSnapshot, trust: Boolean) {
        if (snap.reachability == Reachability.ONLINE) recordTrends(snap)
        val live = ctx.devices.value.firstOrNull { it.device.id == snap.deviceId } ?: return
        if (trust && trustAlerted.add(snap.deviceId)) {
            activity.record(live.device.name, "Presented a different security key than the one you paired (stopped talking to it)")
            report("${live.device.name} presented a different security key than the one you paired. Open it for details.")
            notifier.notify("trust-${snap.deviceId.value}", "${live.device.name} presented a different security key than the one you paired.", urgent = true)
        } else if (!trust) {
            trustAlerted.remove(snap.deviceId)
        }
        val s = live.snapshot ?: return
        val now = Summary(s.reachability, s.state<CapabilityState.PowerState>()?.on, s.state<CapabilityState.PrintJobState>()?.stage, s.state<CapabilityState.MediaState>()?.status)
        val before = lastSummary.put(snap.deviceId, now)
        if (now.stage == PrintStage.PRINTING && before?.stage != PrintStage.PRINTING) printStartedAt.putIfAbsent(snap.deviceId, ctx.clock())
        if (before != null && before.stage != null && before.stage != now.stage &&
            (now.stage == PrintStage.FINISHED || now.stage == PrintStage.FAILED) &&
            (before.stage == PrintStage.PRINTING || before.stage == PrintStage.PREPARING || before.stage == PrintStage.PAUSED)
        ) {
            val took = printStartedAt[snap.deviceId]?.let { " after " + durationText(ctx.clock() - it) } ?: ""
            notifier.notify("print-${snap.deviceId.value}", "${live.device.name}: print ${if (now.stage == PrintStage.FAILED) "failed" else "finished"}$took.", windowMs = 60_000)
        }
        if (before != null && before != now) describeChange(before, now, snap.deviceId)?.let { activity.record(live.device.name, it) }
        if (now.stage != PrintStage.PRINTING && now.stage != PrintStage.PAUSED && now.stage != PrintStage.PREPARING) printStartedAt.remove(snap.deviceId)
        if (before != null && before.reach != Reachability.OFFLINE && now.reach == Reachability.OFFLINE) {
            notifier.notify("offline-${snap.deviceId.value}", "${live.device.name} stopped answering.")
        }
    }

    private fun recordTrends(snap: DeviceSnapshot) {
        val now = ctx.clock()
        val id = snap.deviceId
        snap.state<CapabilityState.ThermostatState>()?.let { recorder.record(id, Metric.INDOOR_TEMPERATURE, it.current.value, now) }
        snap.state<CapabilityState.HumidityReading>()?.let { recorder.record(id, Metric.HUMIDITY, it.relative.value, now) }
        snap.state<CapabilityState.PrinterThermalsState>()?.let { t ->
            t.nozzles.getOrNull(0)?.let { recorder.record(id, Metric.NOZZLE_1, it.current.value, now) }
            t.nozzles.getOrNull(1)?.let { recorder.record(id, Metric.NOZZLE_2, it.current.value, now) }
            recorder.record(id, Metric.BED, t.bed.current.value, now)
            t.chamber?.let { recorder.record(id, Metric.CHAMBER, it.current.value, now) }
        }
        if (now - trendsSavedAt > 10 * 60_000) {
            trendsSavedAt = now
            ctx.store?.putSetting("trends", ctx.json.encodeToString(recorder.export().map { StoredTrend(it.device, it.metric, it.times, it.values) }))
        }
    }

    private fun durationText(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        return when {
            minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
            minutes >= 1 -> "$minutes min"
            else -> "under a minute"
        }
    }

    /** What happened, in words, or null when the change isn't worth a line (a brief "no reply" flicker). */
    private fun describeChange(a: Summary, b: Summary, id: DeviceId): String? {
        val parts = mutableListOf<String>()
        var stateChange = false
        if (a.reach != Reachability.OFFLINE && b.reach == Reachability.OFFLINE) parts += "Went offline"
        if (a.reach == Reachability.OFFLINE && b.reach == Reachability.ONLINE) parts += "Back online"
        if (a.power != null && b.power != null && a.power != b.power) { parts += if (b.power) "Turned on" else "Turned off"; stateChange = true }
        if (b.stage != null && a.stage != b.stage) {
            val took = if (b.stage == PrintStage.FINISHED || b.stage == PrintStage.FAILED) printStartedAt[id]?.let { " after " + durationText(ctx.clock() - it) } ?: "" else ""
            parts += "Printer: ${b.stage.name.lowercase()}$took"; stateChange = true
        }
        if (b.media != null && a.media != b.media && b.media != PlaybackStatus.BUFFERING) {
            parts += when (b.media) {
                PlaybackStatus.PLAYING -> "Playing"
                PlaybackStatus.PAUSED -> "Paused"
                else -> "Stopped"
            }
            stateChange = true
        }
        if (parts.isEmpty()) return null
        val cause = if (!stateChange) "" else if (ctx.clock() - (lastCommandAt[id] ?: 0L) < 15_000) " (from Patchbay)" else " (at the device or another app)"
        return parts.joinToString("; ") + cause
    }
}
