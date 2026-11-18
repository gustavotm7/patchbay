package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Scenes that start by themselves: at a time of day or sunrise/sunset on chosen days ([tick], called every 15 seconds, with catch-up after sleep), or when a device does
 * something ([onDeviceEvent]). They run only while Patchbay runs.
 *
 * One limit keeps a device-triggered automation from running away: while an automation's scene is running, and for [CHAIN_WINDOW_MS] afterwards,
 * device events start nothing. The devices that scene just changed would otherwise start more scenes, and two automations that undo each other
 * would fight forever. Automations waiting for the same event all run together. A scene you run yourself can still start automations.
 *
 * An automation may also carry an [AutomationCondition] (a daily window and/or home or away). It is checked at the moment the automation would run, for both kinds;
 * when it does not hold, Activity says why and nothing runs.
 */
internal class Automations(
    private val ctx: HomeContext,
    private val scenes: Scenes,
    private val activity: ActivityLog,
    private val location: StateFlow<HomeLocation?>,
    private val isAway: () -> Boolean,
    private val runScene: suspend (String) -> PatchbayResult<Unit>,
) {
    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    val automations: StateFlow<List<Automation>> = _automations.asStateFlow()
    @Volatile private var stored: List<StoredAutomation> = emptyList()
    /** For each enabled automation, the time of the last tick that looked at it (see [tick]). */
    private val checkedUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var chainBlockedUntil = Long.MIN_VALUE

    companion object {
        const val CHAIN_WINDOW_MS = 30_000L

        /** How late a scheduled time may still run (after sleep or Doze); older than this it is skipped. */
        const val GRACE_MS = 10 * 60_000L
    }

    fun load() {
        val raw = ctx.store?.setting("automations") ?: return
        val decoded: List<StoredAutomation> = try { ctx.json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
        // A trigger this version doesn't know (a newer file, or damage) is dropped, never fatal.
        stored = decoded.filter { a -> a.trigger == null || DeviceEvent.entries.any { it.name == a.trigger.event } }
        if (stored.size != decoded.size) ctx.log?.warn("home", "dropped ${decoded.size - stored.size} automations with an unknown trigger")
        // A condition that can no longer be read must not quietly turn into "always": switch that automation off, where its owner will see it.
        val damaged = stored.count { it.enabled && Conditions.isDamaged(it.condition) }
        if (damaged > 0) {
            stored = stored.map { if (it.enabled && Conditions.isDamaged(it.condition)) it.copy(enabled = false) else it }
            ctx.log?.warn("home", "switched off $damaged automations whose condition could not be read")
        }
        publish()
    }

    fun publish() {
        _automations.value = stored.map { a ->
            val trigger = a.trigger?.let { t ->
                DeviceTrigger(DeviceId(t.device), deviceName(t.device), DeviceEvent.entries.first { it.name == t.event })
            }
            Automation(a.id, a.name, a.sceneId, scenes.find(a.sceneId)?.name, a.hour, a.minute, a.days.toSet(), a.enabled, a.sun, a.offset, trigger, Conditions.fromStored(a.condition))
        }
    }

    private fun persist() {
        ctx.store?.putSetting("automations", ctx.json.encodeToString(stored))
        publish()
    }

    private fun deviceName(id: String): String? = ctx.devices.value.firstOrNull { it.device.id.value == id }?.device?.name

    private fun cleanName(name: String): String? =
        name.trim().replace(Regex("\\s+"), " ").takeIf { it.length in 1..32 && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '-' } }

    /** Null when the condition is acceptable (or absent); otherwise what is wrong with it. */
    private fun conditionProblem(c: AutomationCondition?): PatchbayError? {
        val w = c?.window ?: return null
        fun bad(p: DayPoint) = when (p) {
            is DayPoint.Clock -> p.hour !in 0..23 || p.minute !in 0..59
            is DayPoint.Sun -> p.offsetMinutes !in -180..180
        }
        if (bad(w.from) || bad(w.until)) return PatchbayError.InvalidArgument("Use times between 00:00 and 23:59, and sunrise or sunset shifted by up to 3 hours")
        if (w.from == w.until) return PatchbayError.InvalidArgument("The window must start and end at different times")
        if (w.needsLocation && location.value == null) return PatchbayError.InvalidArgument("Set your home location in Settings first")
        return null
    }

    private fun check(a: StoredAutomation, nowMillis: Long): Conditions.Verdict =
        Conditions.check(Conditions.fromStored(a.condition), nowMillis, ctx.zone, location.value, isAway())

    /** Runs [sceneId] and shuts device events out until it has finished and settled (see the class comment). */
    private fun launchScene(sceneId: String) {
        chainBlockedUntil = ctx.clock() + CHAIN_WINDOW_MS
        ctx.scope.launch {
            try { runScene(sceneId) } finally { chainBlockedUntil = ctx.clock() + CHAIN_WINDOW_MS }
        }
    }

    suspend fun saveDeviceAutomation(name: String, sceneId: String, device: DeviceId, event: DeviceEvent, condition: AutomationCondition? = null): PatchbayResult<Unit> {
        val clean = cleanName(name)
            ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("Give the automation a short name using letters, numbers, spaces or hyphens"))
        if (scenes.find(sceneId) == null) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick a scene to run"))
        val live = ctx.devices.value.firstOrNull { it.device.id == device }
            ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick a device"))
        if (event !in DeviceEvent.availableFor(live.device.capabilities)) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("${live.device.name} can't tell Patchbay when it ${event.label}"))
        }
        conditionProblem(condition)?.let { return PatchbayResult.Err(it) }
        synchronized(this) {
            stored = stored + StoredAutomation(
                java.util.UUID.randomUUID().toString().take(8), clean, sceneId, 0, 0, (1..7).toList(), true, null, 0, StoredTrigger(device.value, event.name),
                condition?.takeUnless { it.isEmpty }?.let(Conditions::toStored),
            )
            persist()
        }
        return PatchbayResult.Ok(Unit)
    }

    /** A device did something. Starts the scene of every enabled automation waiting for it, unless the limits in the class comment say not to. */
    fun onDeviceEvent(device: DeviceId, event: DeviceEvent) {
        // Decided once per event, so automations waiting for the same event all run.
        val blocked = ctx.clock() < chainBlockedUntil
        for (a in stored) {
            val t = a.trigger ?: continue
            if (!a.enabled || t.device != device.value || t.event != event.name) continue
            val who = deviceName(t.device) ?: "A device"
            val title = "Automation: ${a.name}"
            val scene = scenes.find(a.sceneId)
            if (scene == null) { activity.record(title, "Skipped: its scene was deleted"); continue }
            (check(a, ctx.clock()) as? Conditions.Verdict.Fails)?.let { activity.record(title, "Not run: $who ${event.label}, but it ${it.why}"); continue }
            if (blocked) {
                activity.record(title, "Not run: $who ${event.label} right after an automation ran, and automations don't start each other")
                continue
            }
            activity.record(title, "Running scene ${scene.name} because $who ${event.label}")
            launchScene(scene.id)
        }
    }

    suspend fun save(name: String, sceneId: String, hour: Int, minute: Int, days: Set<Int>, sun: String?, offsetMinutes: Int, condition: AutomationCondition? = null): PatchbayResult<Unit> {
        val clean = cleanName(name)
            ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("Give the automation a short name using letters, numbers, spaces or hyphens"))
        if (sun != null) {
            if (sun != "sunrise" && sun != "sunset") return PatchbayResult.Err(PatchbayError.InvalidArgument("Choose sunrise or sunset"))
            if (offsetMinutes !in -180..180) return PatchbayResult.Err(PatchbayError.InvalidArgument("The offset can be up to 3 hours before or after"))
            if (location.value == null) return PatchbayResult.Err(PatchbayError.InvalidArgument("Set your home location in Settings first"))
        } else if (hour !in 0..23 || minute !in 0..59) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("The time must be between 00:00 and 23:59"))
        }
        if (days.isEmpty() || days.any { it !in 1..7 }) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick at least one day"))
        if (scenes.find(sceneId) == null) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick a scene to run"))
        conditionProblem(condition)?.let { return PatchbayResult.Err(it) }
        synchronized(this) {
            stored = stored + StoredAutomation(
                java.util.UUID.randomUUID().toString().take(8), clean, sceneId, if (sun == null) hour else 0, if (sun == null) minute else 0,
                days.sorted(), true, sun, if (sun == null) 0 else offsetMinutes,
                condition = condition?.takeUnless { it.isEmpty }?.let(Conditions::toStored),
            )
            persist()
        }
        return PatchbayResult.Ok(Unit)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        synchronized(this) {
            stored = stored.map { if (it.id == id) it.copy(enabled = enabled) else it }
            persist()
        }
    }

    suspend fun delete(id: String) {
        synchronized(this) {
            stored = stored.filterNot { it.id == id }
            persist()
        }
    }

    /**
     * Runs every enabled automation whose time came up since it was last looked at, once. Called every 15 seconds, but a sleeping laptop or a phone in Doze
     * can leave a gap of many minutes: a time that fell inside the gap still runs, late, as long as it is at most [GRACE_MS] old (Activity says how late). A time
     * that passed before the automation was created, enabled or Patchbay started is never run, and nothing runs twice, because each moment falls in exactly one gap.
     * A local time that does not exist (the clocks went forward) runs at the first moment after it.
     */
    fun tick(nowMillis: Long) {
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(ctx.zone).toLocalDate()
        val seen = HashSet<String>()
        for (a in stored) {
            if (!a.enabled) continue
            seen += a.id
            // First look at an automation: only the last minute counts, which is what "at exactly this minute" meant before catch-up existed.
            val from = checkedUntil[a.id] ?: (nowMillis - 60_000L)
            checkedUntil[a.id] = nowMillis
            // The grace window is far shorter than a day, so today and yesterday are the only dates that can hold a due moment.
            for (day in listOf(today.minusDays(1), today)) {
                if (day.dayOfWeek.value !in a.days) continue
                val due = scheduledAt(a, day) ?: continue
                if (due <= from || due > nowMillis || nowMillis - due >= GRACE_MS) continue
                val scene = scenes.find(a.sceneId)
                if (scene == null) {
                    activity.record("Automation: ${a.name}", "Skipped: its scene was deleted")
                    continue
                }
                val late = (nowMillis - due) / 60_000L
                val lateNote = if (late >= 2) " ($late minutes late: Patchbay was asleep or busy at the time)" else ""
                (check(a, nowMillis) as? Conditions.Verdict.Fails)?.let { activity.record("Automation: ${a.name}", "Not run: it ${it.why}$lateNote"); continue }
                activity.record("Automation: ${a.name}", "Running scene ${scene.name}$lateNote")
                launchScene(scene.id)
            }
        }
        checkedUntil.keys.retainAll(seen) // a disabled or deleted automation starts afresh when it comes back
    }

    /** When [a] is due on [day] (epoch millis), or null for a sunrise or sunset that cannot be worked out (no location, no sunrise at this latitude). */
    private fun scheduledAt(a: StoredAutomation, day: java.time.LocalDate): Long? {
        val sunName = a.sun ?: return day.atTime(a.hour, a.minute).atZone(ctx.zone).toInstant().toEpochMilli()
        val loc = location.value ?: return null
        val (rise, set) = SunTimes.compute(loc.latitude, loc.longitude, day.dayOfYear) ?: return null
        val minutes = if (sunName == "sunrise") rise else set
        val instant = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds((minutes * 60).toLong() + a.offset * 60L)
        // Sun times are minutes after 00:00 UTC; a result that lands on another local date belongs to that date's own entry.
        // Whole minutes, as the time-of-day automations are: a sunset at 16:44:37 is due at 16:44.
        val due = instant.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        return if (due.atZone(ctx.zone).toLocalDate() == day) due.toEpochMilli() else null
    }
}
