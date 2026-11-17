package dev.patchbay.core.home

import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Lights whose white colour follows the day: cool at midday, warm in the evening, never overriding a change the owner makes. */
internal class AdaptiveLighting(
    private val ctx: HomeContext,
    private val activity: ActivityLog,
    private val send: suspend (DeviceId, Command) -> PatchbayResult<Unit>,
) {
    private val _adaptive = MutableStateFlow<Set<DeviceId>>(emptySet())
    val adaptive: StateFlow<Set<DeviceId>> = _adaptive.asStateFlow()
    private val applied = ConcurrentHashMap<DeviceId, Int>()
    private val appliedAt = ConcurrentHashMap<DeviceId, Long>()
    private val overridden = ConcurrentHashMap.newKeySet<DeviceId>()

    fun load() {
        ctx.store?.setting("adaptive")?.let { raw ->
            _adaptive.value = try { ctx.json.decodeFromString<List<String>>(raw).map(::DeviceId).toSet() } catch (e: Exception) { emptySet() }
        }
    }

    suspend fun set(device: DeviceId, on: Boolean) {
        _adaptive.update { if (on) it + device else it - device }
        applied.remove(device); overridden.remove(device)
        ctx.store?.putSetting("adaptive", ctx.json.encodeToString(_adaptive.value.map { it.value }))
        val name = ctx.devices.value.firstOrNull { it.device.id == device }?.device?.name ?: return
        activity.record(name, if (on) "Adaptive lighting turned on" else "Adaptive lighting turned off")
    }

    /**
     * Moves the white colour of each adaptive light that is on toward the day's curve. A light you change yourself is left alone
     * until you switch it off and on again. Colour-mode lights are never touched.
     */
    fun tick(nowMillis: Long) {
        val t = java.time.Instant.ofEpochMilli(nowMillis).atZone(ctx.zone)
        val minute = t.hour * 60 + t.minute
        for (id in _adaptive.value) {
            val live = ctx.devices.value.firstOrNull { it.device.id == id } ?: continue
            val snap = live.snapshot ?: continue
            if (snap.reachability != dev.patchbay.core.model.Reachability.ONLINE) continue
            if (snap.state<dev.patchbay.core.model.CapabilityState.PowerState>()?.on != true) {
                applied.remove(id); overridden.remove(id); continue
            }
            val k = snap.state<dev.patchbay.core.model.CapabilityState.ColourTemperatureState>()?.kelvin?.value ?: continue
            val last = applied[id]
            if (last != null && nowMillis - (appliedAt[id] ?: 0L) > 20_000 && kotlin.math.abs(k - last) > 250) overridden += id
            if (id in overridden) continue
            val cap = live.device.capability<dev.patchbay.core.model.Capability.ColourTemperature>() ?: continue
            val target = AdaptiveCurve.kelvinAt(minute).coerceIn(cap.warmest.value, cap.coolest.value)
            if (kotlin.math.abs(k - target) > 100) {
                applied[id] = target; appliedAt[id] = nowMillis
                ctx.scope.launch { send(id, Command.SetColourTemperature(dev.patchbay.core.model.Kelvin(target))) }
            }
        }
    }
}
