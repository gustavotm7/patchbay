package dev.patchbay.core.home

import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Away mode: switch everything off, and optionally imitate someone being home in the evening with a few lights. */
internal class AwayMode(
    private val ctx: HomeContext,
    private val activity: ActivityLog,
    private val send: suspend (DeviceId, Command) -> PatchbayResult<Unit>,
    private val allOff: suspend () -> PatchbayResult<Int>,
) {
    private val _away = MutableStateFlow(AwayState())
    val away: StateFlow<AwayState> = _away.asStateFlow()
    private val nextFlip = ConcurrentHashMap<DeviceId, Long>()
    @Volatile private var simOffDay = -1L

    fun load() {
        ctx.store?.setting("away")?.let { raw ->
            _away.value = try { ctx.json.decodeFromString<StoredAway>(raw).let { AwayState(it.active, it.simulate.map(::DeviceId).toSet()) } } catch (e: Exception) { AwayState() }
        }
    }

    suspend fun set(active: Boolean, simulate: Set<DeviceId>) {
        val was = _away.value.active
        _away.value = AwayState(active, if (active) simulate else emptySet())
        ctx.store?.putSetting("away", ctx.json.encodeToString(StoredAway(active, _away.value.simulate.map { it.value })))
        nextFlip.clear()
        if (active && !was) {
            activity.record("Away mode", "Turned on" + if (simulate.isNotEmpty()) " (simulating presence with ${simulate.size} lights)" else "")
            allOff()
        } else if (!active && was) {
            activity.record("Away mode", "Turned off")
        }
    }

    /**
     * While away with simulation on: between 18:00 and 22:59 each chosen light flips at irregular intervals (20 to 60 minutes),
     * and at 23:00 they all go off once.
     */
    fun tick(nowMillis: Long) {
        val a = _away.value
        if (!a.active || a.simulate.isEmpty()) return
        val t = java.time.Instant.ofEpochMilli(nowMillis).atZone(ctx.zone)
        when {
            t.hour in 18..22 -> for (id in a.simulate) {
                val live = ctx.devices.value.firstOrNull { it.device.id == id } ?: continue
                val snap = live.snapshot ?: continue
                if (snap.reachability != dev.patchbay.core.model.Reachability.ONLINE) continue
                val due = nextFlip.getOrPut(id) { nowMillis + ctx.random.nextInt(1, 40) * 60_000L }
                if (nowMillis < due) continue
                nextFlip[id] = nowMillis + ctx.random.nextInt(20, 61) * 60_000L
                val on = snap.state<dev.patchbay.core.model.CapabilityState.PowerState>()?.on == true
                activity.record("Away mode", "Simulated presence: ${live.device.name} ${if (on) "off" else "on"}")
                ctx.scope.launch { send(id, Command.SetPower(!on)) }
            }
            t.hour == 23 && simOffDay != t.toLocalDate().toEpochDay() -> {
                simOffDay = t.toLocalDate().toEpochDay()
                nextFlip.clear()
                for (id in a.simulate) ctx.scope.launch { send(id, Command.SetPower(false)) }
                activity.record("Away mode", "Simulated presence ended for the night")
            }
            t.hour < 18 -> nextFlip.clear()
        }
    }
}
