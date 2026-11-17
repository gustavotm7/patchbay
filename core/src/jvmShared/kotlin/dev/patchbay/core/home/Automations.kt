package dev.patchbay.core.home

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Scenes on a schedule: a time of day or sunrise/sunset on chosen days. They run only while Patchbay runs; [tick] is called every 15 seconds. */
internal class Automations(
    private val ctx: HomeContext,
    private val scenes: Scenes,
    private val activity: ActivityLog,
    private val location: StateFlow<HomeLocation?>,
    private val runScene: suspend (String) -> PatchbayResult<Unit>,
) {
    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    val automations: StateFlow<List<Automation>> = _automations.asStateFlow()
    @Volatile private var stored: List<StoredAutomation> = emptyList()
    private val lastRun = ConcurrentHashMap<String, Long>()

    fun load() {
        val raw = ctx.store?.setting("automations") ?: return
        stored = try { ctx.json.decodeFromString(raw) } catch (e: Exception) { emptyList() }
        publish()
    }

    fun publish() {
        _automations.value = stored.map { a ->
            Automation(a.id, a.name, a.sceneId, scenes.find(a.sceneId)?.name, a.hour, a.minute, a.days.toSet(), a.enabled, a.sun, a.offset)
        }
    }

    private fun persist() {
        ctx.store?.putSetting("automations", ctx.json.encodeToString(stored))
        publish()
    }

    suspend fun save(name: String, sceneId: String, hour: Int, minute: Int, days: Set<Int>, sun: String?, offsetMinutes: Int): PatchbayResult<Unit> {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 1..32 || !clean.all { it.isLetterOrDigit() || it == ' ' || it == '-' }) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("Give the automation a short name using letters, numbers, spaces or hyphens"))
        }
        if (sun != null) {
            if (sun != "sunrise" && sun != "sunset") return PatchbayResult.Err(PatchbayError.InvalidArgument("Choose sunrise or sunset"))
            if (offsetMinutes !in -180..180) return PatchbayResult.Err(PatchbayError.InvalidArgument("The offset can be up to 3 hours before or after"))
            if (location.value == null) return PatchbayResult.Err(PatchbayError.InvalidArgument("Set your home location in Settings first"))
        } else if (hour !in 0..23 || minute !in 0..59) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("The time must be between 00:00 and 23:59"))
        }
        if (days.isEmpty() || days.any { it !in 1..7 }) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick at least one day"))
        if (scenes.find(sceneId) == null) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick a scene to run"))
        synchronized(this) {
            stored = stored + StoredAutomation(
                java.util.UUID.randomUUID().toString().take(8), clean, sceneId, if (sun == null) hour else 0, if (sun == null) minute else 0,
                days.sorted(), true, sun, if (sun == null) 0 else offsetMinutes,
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

    /** Runs every enabled automation whose time is this minute on a matching day, once per minute. */
    fun tick(nowMillis: Long) {
        val t = java.time.Instant.ofEpochMilli(nowMillis).atZone(ctx.zone)
        val minuteKey = t.toLocalDate().toEpochDay() * 1_440 + t.hour * 60 + t.minute
        for (a in stored) {
            if (!a.enabled || t.dayOfWeek.value !in a.days) continue
            val sunName = a.sun
            if (sunName == null) {
                if (t.hour != a.hour || t.minute != a.minute) continue
            } else {
                val loc = location.value ?: continue
                val (rise, set) = SunTimes.compute(loc.latitude, loc.longitude, t.dayOfYear) ?: continue
                val minutes = if (sunName == "sunrise") rise else set
                val instant = t.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds((minutes * 60).toLong() + a.offset * 60L)
                val local = instant.atZone(ctx.zone)
                if (local.toLocalDate() != t.toLocalDate() || local.hour != t.hour || local.minute != t.minute) continue
            }
            if (lastRun[a.id] == minuteKey) continue
            lastRun[a.id] = minuteKey
            val scene = scenes.find(a.sceneId)
            if (scene == null) {
                activity.record("Automation: ${a.name}", "Skipped: its scene was deleted")
                continue
            }
            activity.record("Automation: ${a.name}", "Running scene ${scene.name}")
            ctx.scope.launch { runScene(scene.id) }
        }
    }
}
