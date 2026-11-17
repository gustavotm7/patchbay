package dev.patchbay.core.home

import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saved scenes: the state of some lights, plugs, thermostats and TV volumes, put back with one tap. Nothing that moves, records, locks or heats a
 * printer can be part of one.
 */
internal class Scenes(
    private val ctx: HomeContext,
    private val activity: ActivityLog,
    private val send: suspend (DeviceId, Command) -> PatchbayResult<Unit>,
) {
    private val _scenes = MutableStateFlow<List<Scene>>(emptyList())
    val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()
    @Volatile private var stored: List<StoredScene> = emptyList()

    /** Told after a scene is deleted, because automations show the name of the scene they run. */
    var onDeleted: () -> Unit = {}

    fun find(id: String): StoredScene? = stored.firstOrNull { it.id == id }

    fun load() {
        val raw = ctx.store?.setting("scenes") ?: return
        stored = try {
            ctx.json.decodeFromString<List<StoredScene>>(raw)
        } catch (e: Exception) {
            ctx.log?.warn("home", "saved scenes could not be read; starting with none")
            emptyList()
        }
        _scenes.value = stored.map { s -> Scene(s.id, s.name, s.steps.map { DeviceId(it.device) }) }
    }

    private fun persist() {
        ctx.store?.putSetting("scenes", ctx.json.encodeToString(stored))
        _scenes.value = stored.map { s -> Scene(s.id, s.name, s.steps.map { DeviceId(it.device) }) }
    }

    suspend fun save(name: String, devices: List<DeviceId>): PatchbayResult<Unit> {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 1..32 || !clean.all { it.isLetterOrDigit() || it == ' ' || it == '-' }) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("Give the scene a short name using letters, numbers, spaces or hyphens"))
        }
        val steps = devices.mapNotNull { id ->
            val live = ctx.devices.value.firstOrNull { it.device.id == id } ?: return@mapNotNull null
            // Lights, plugs, thermostats and the volume of TVs and speakers only: nothing that moves, records, locks or heats a printer can be part of a scene.
            val kind = live.device.kind
            val isThermostat = kind == dev.patchbay.core.model.DeviceKind.THERMOSTAT
            val isVolume = kind == dev.patchbay.core.model.DeviceKind.TV || kind == dev.patchbay.core.model.DeviceKind.MEDIA_RECEIVER || kind == dev.patchbay.core.model.DeviceKind.SPEAKER
            if (kind != dev.patchbay.core.model.DeviceKind.LIGHT && kind != dev.patchbay.core.model.DeviceKind.PLUG && !isThermostat && !isVolume) return@mapNotNull null
            val snap = live.snapshot ?: return@mapNotNull null
            if (snap.reachability != dev.patchbay.core.model.Reachability.ONLINE) return@mapNotNull null
            if (isVolume) {
                val v = snap.state<dev.patchbay.core.model.CapabilityState.VolumeState>() ?: return@mapNotNull null
                return@mapNotNull StoredStep(device = id.value, power = true, volume = v.level, muted = v.muted)
            }
            if (isThermostat) {
                val t = snap.state<dev.patchbay.core.model.CapabilityState.ThermostatState>() ?: return@mapNotNull null
                return@mapNotNull StoredStep(device = id.value, power = t.mode != dev.patchbay.core.model.HvacMode.OFF, hvacMode = t.mode.name, heat = t.heatSetpoint?.value, cool = t.coolSetpoint?.value)
            }
            val power = snap.state<dev.patchbay.core.model.CapabilityState.PowerState>() ?: return@mapNotNull null
            StoredStep(
                device = id.value, power = power.on,
                brightness = if (power.on) snap.state<dev.patchbay.core.model.CapabilityState.BrightnessState>()?.level?.value else null,
                kelvin = if (power.on) snap.state<dev.patchbay.core.model.CapabilityState.ColourTemperatureState>()?.kelvin?.value else null,
                rgb = if (power.on && snap.state<dev.patchbay.core.model.CapabilityState.ColourTemperatureState>() == null) {
                    snap.state<dev.patchbay.core.model.CapabilityState.RgbState>()?.colour?.let { listOf(it.r, it.g, it.b) }
                } else null,
            )
        }
        if (steps.isEmpty()) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick at least one light or plug that is reachable right now"))
        synchronized(this) {
            val existing = stored.firstOrNull { it.name.equals(clean, ignoreCase = true) }
            val scene = StoredScene(existing?.id ?: java.util.UUID.randomUUID().toString().take(8), clean, steps)
            stored = stored.filterNot { it.id == scene.id } + scene
            persist()
        }
        ctx.log?.info("home", "saved a scene with ${steps.size} devices")
        return PatchbayResult.Ok(Unit)
    }

    suspend fun run(id: String): PatchbayResult<Unit> {
        val scene = stored.firstOrNull { it.id == id } ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("unknown scene"))
        var failed = 0
        for (step in scene.steps) {
            val target = DeviceId(step.device)
            val hvac = step.hvacMode?.let { m -> dev.patchbay.core.model.HvacMode.entries.firstOrNull { it.name == m } }
            val commands = if (step.volume != null) buildList<Command> {
                add(Command.SetVolume(step.volume.coerceIn(0, 100)))
                step.muted?.let { add(Command.SetMute(it)) }
            } else if (step.hvacMode != null) buildList<Command> {
                if (hvac != null) {
                    add(Command.SetHvacMode(hvac))
                    // Setpoints only for the mode being restored; the thermostat ignores the rest anyway.
                    val h = step.heat?.takeIf { it.isFinite() && it in dev.patchbay.core.model.Celsius.MIN..dev.patchbay.core.model.Celsius.MAX }?.let { dev.patchbay.core.model.Celsius(it) }
                    val k = step.cool?.takeIf { it.isFinite() && it in dev.patchbay.core.model.Celsius.MIN..dev.patchbay.core.model.Celsius.MAX }?.let { dev.patchbay.core.model.Celsius(it) }
                    when (hvac) {
                        dev.patchbay.core.model.HvacMode.HEAT -> if (h != null) add(Command.SetSetpoints(h, null))
                        dev.patchbay.core.model.HvacMode.COOL -> if (k != null) add(Command.SetSetpoints(null, k))
                        dev.patchbay.core.model.HvacMode.AUTO -> if (h != null || k != null) add(Command.SetSetpoints(h, k))
                        dev.patchbay.core.model.HvacMode.OFF -> {}
                    }
                }
            } else buildList<Command> {
                add(Command.SetPower(step.power))
                if (step.power) {
                    step.brightness?.let { add(Command.SetBrightness(dev.patchbay.core.model.Percent(it))) }
                    val k = step.kelvin
                    val rgb = step.rgb
                    if (k != null) add(Command.SetColourTemperature(dev.patchbay.core.model.Kelvin(k)))
                    else if (rgb != null && rgb.size == 3) add(Command.SetColour(dev.patchbay.core.model.Rgb(rgb[0], rgb[1], rgb[2])))
                }
            }
            var ok = true
            for (c in commands) {
                if (send(target, c) is PatchbayResult.Err) ok = false
                delay(40)
            }
            if (!ok) failed++
        }
        activity.record("Scene: ${scene.name}", "Ran (${scene.steps.size - failed} of ${scene.steps.size} devices responded)")
        ctx.log?.info("home", "ran a scene: ${scene.steps.size - failed} of ${scene.steps.size} devices responded")
        return if (failed == 0) PatchbayResult.Ok(Unit)
        else PatchbayResult.Err(PatchbayError.Unreachable("$failed of ${scene.steps.size} devices didn't respond"))
    }

    fun delete(id: String) {
        synchronized(this) {
            stored = stored.filterNot { it.id == id }
            persist()
        }
        onDeleted()
    }
}
