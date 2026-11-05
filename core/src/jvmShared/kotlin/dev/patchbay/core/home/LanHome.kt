package dev.patchbay.core.home

import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.log.SessionLog
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.CommandValidator
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.net.NetGuardStats
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** [LiveHome] over real adapters. One instance per process, created by Bootstrap. */
public class LanHome(
    private val netGuard: NetGuard,
    private val adapters: List<DeviceAdapter>,
    private val scope: CoroutineScope,
    private val log: SessionLog?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LiveHome {

    private val _devices = MutableStateFlow<List<LiveDevice>>(emptyList())
    override val devices: StateFlow<List<LiveDevice>> = _devices.asStateFlow()
    private val _scan = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scan.asStateFlow()
    override val netStats: StateFlow<NetGuardStats> get() = netGuard.stats
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    private val owners = ConcurrentHashMap<DeviceId, DeviceAdapter>()
    private val observers = ConcurrentHashMap<DeviceId, Job>()

    override suspend fun scan(): PatchbayResult<Int> {
        _scan.value = ScanState.SCANNING
        try {
            for (a in adapters) {
                a.discover().collect { found -> add(a, found) }
            }
        } catch (e: Exception) {
            // Adapters return errors as values; this only catches programming errors, and reports them.
            _scan.value = ScanState.FAILED
            report("scan failed: ${e.javaClass.simpleName}")
            return PatchbayResult.Err(PatchbayError.ProtocolViolation("scan failed"))
        }
        _scan.value = ScanState.DONE
        log?.info("home", "scan finished: ${_devices.value.size} device(s)")
        return PatchbayResult.Ok(_devices.value.size)
    }

    private fun add(adapter: DeviceAdapter, found: DiscoveredDevice) {
        val id = adapter.deviceId(found)
        if (_devices.value.any { it.device.id == id }) return
        val sameModel = _devices.value.count { it.device.model == found.model }
        val device = Device(
            id = id,
            name = listOfNotNull(found.vendor, found.model).joinToString(" ") + if (sameModel > 0) " ${sameModel + 1}" else "",
            kind = found.kind, vendor = found.vendor, model = found.model, roomId = null, placement = null,
            controlPath = found.controlPath, capabilities = adapter.capabilities(id), adapter = adapter.id,
        )
        owners[id] = adapter
        _devices.update { it + LiveDevice(device, null) }
        log?.info("home", "found ${device.name} at ${found.address}")
        observers[id] = scope.launch { adapter.observe(id).collect { snap -> updateSnapshot(snap) } }
    }

    private fun updateSnapshot(snap: DeviceSnapshot) {
        _devices.update { list -> list.map { if (it.device.id == snap.deviceId) it.copy(snapshot = merge(it.snapshot, snap)) else it } }
    }

    /** An unreachable poll keeps the last known values, marked with the new reachability. */
    private fun merge(old: DeviceSnapshot?, new: DeviceSnapshot): DeviceSnapshot =
        if (new.states.isEmpty() && old != null) old.copy(reachability = new.reachability, observedAt = new.observedAt) else new

    override suspend fun send(device: DeviceId, command: Command): PatchbayResult<Unit> {
        val live = _devices.value.firstOrNull { it.device.id == device }
            ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown device"))
        val ok = CommandValidator.validate(live.device, command, live.snapshot)
        if (ok is PatchbayResult.Err) return ok
        val adapter = owners[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("no adapter"))
        val r = adapter.command(device, command)
        if (r is PatchbayResult.Ok) optimistic(device, command)
        else report((r as PatchbayResult.Err).error.message)
        return r
    }

    /** Shows the commanded value immediately; the next poll confirms or corrects it. */
    private fun optimistic(device: DeviceId, command: Command) {
        val apply: (List<CapabilityState>) -> List<CapabilityState> = { states ->
            val replacement: CapabilityState? = when (command) {
                is Command.SetPower -> CapabilityState.PowerState(command.on)
                is Command.SetBrightness -> CapabilityState.BrightnessState(command.level)
                is Command.SetColour -> CapabilityState.RgbState(command.colour)
                is Command.SetColourTemperature -> CapabilityState.ColourTemperatureState(command.kelvin)
                else -> null
            }
            if (replacement == null) states else {
                val colourModes = setOf(CapabilityState.RgbState::class, CapabilityState.ColourTemperatureState::class)
                val drop = if (replacement::class in colourModes) colourModes else setOf(replacement::class)
                states.filterNot { it::class in drop } + replacement
            }
        }
        _devices.update { list ->
            list.map {
                if (it.device.id != device) it else {
                    val s = it.snapshot ?: DeviceSnapshot(device, Reachability.ONLINE, emptyList(), EpochMillis(clock()))
                    it.copy(snapshot = s.copy(states = apply(s.states)))
                }
            }
        }
    }

    internal fun reportStartupProblem(message: String): Unit = report(message)

    private fun report(message: String) {
        _problem.value = message
        log?.warn("home", message)
    }
}
