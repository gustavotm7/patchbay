package dev.patchbay.core.home

import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
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
import dev.patchbay.core.store.HomeStore
import dev.patchbay.core.store.StoredDevice
import dev.patchbay.core.store.StoredPairing
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.discovery.NetworkCensus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    private val store: HomeStore? = null,
    private val censusEngine: NetworkCensus? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LiveHome {

    private val _devices = MutableStateFlow<List<LiveDevice>>(emptyList())
    override val devices: StateFlow<List<LiveDevice>> = _devices.asStateFlow()
    private val _scan = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scan.asStateFlow()
    override val netStats: StateFlow<NetGuardStats> get() = netGuard.stats
    private val _census = MutableStateFlow<List<Finding>>(emptyList())
    override val census: StateFlow<List<Finding>> = _census.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    private val owners = ConcurrentHashMap<DeviceId, DeviceAdapter>()
    private val observers = ConcurrentHashMap<DeviceId, Job>()

    init {
        restoreSaved()
    }

    /** Devices saved by earlier sessions appear immediately and are polled; no scan needed. */
    private fun restoreSaved() {
        val saved = store?.devices() ?: return
        for (sd in saved) {
            val adapter = adapters.firstOrNull { it.id.value == sd.adapter } ?: continue
            val pairing = store.pairing(sd.id)?.let { Pairing(it.deviceId, it.secret, it.pinnedSha256) }
            if (adapter.restore(sd.id, sd.address, pairing) is PatchbayResult.Err) continue
            val device = Device(
                id = sd.id, name = sd.name, kind = sd.kind, vendor = sd.vendor, model = sd.model, roomId = sd.roomId,
                placement = null, controlPath = sd.controlPath, capabilities = adapter.capabilities(sd.id), adapter = adapter.id,
            )
            val unpaired = adapter.requiresPairing && pairing == null
            if (unpaired) {
                pendingPairing[sd.id] = DiscoveredDevice(
                    adapter.id, sd.adapterKey, sd.address, sd.port, sd.kind, sd.vendor, sd.model, sd.name, sd.controlPath, EpochMillis(sd.lastSeen),
                )
            }
            track(adapter, device, sd.address, observe = !unpaired)
        }
        if (saved.isNotEmpty()) log?.info("home", "restored ${_devices.value.size} saved device(s)")
    }

    private val pendingPairing = ConcurrentHashMap<DeviceId, DiscoveredDevice>()

    private fun track(adapter: DeviceAdapter, device: Device, address: String?, observe: Boolean = true) {
        owners[device.id] = adapter
        _devices.update { it + LiveDevice(device, null, address, needsPairing = !observe) }
        if (observe) startObserving(adapter, device.id)
    }

    private fun startObserving(adapter: DeviceAdapter, id: DeviceId) {
        observers[id]?.cancel()
        observers[id] = scope.launch { adapter.observe(id).collect { snap -> updateSnapshot(snap) } }
    }

    override suspend fun pair(device: DeviceId, prompt: PairingPrompt): PatchbayResult<Unit> {
        val adapter = owners[device] ?: return PatchbayResult.Err(PatchbayError.Unreachable("unknown device"))
        val found = pendingPairing[device] ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("device is already paired"))
        val pairing = when (val r = adapter.pair(found, prompt)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { report(r.error.message); return r }
        }
        store?.savePairing(StoredPairing(device, pairing.secret, pairing.pinnedFingerprint, clock()))
        adapter.restore(device, found.address, pairing)
        pendingPairing.remove(device)
        _devices.update { list -> list.map { if (it.device.id == device) it.copy(needsPairing = false) else it } }
        log?.info("home", "paired ${found.displayName ?: found.model ?: "device"} (key pinned)")
        startObserving(adapter, device)
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun scan(): PatchbayResult<Int> {
        _scan.value = ScanState.SCANNING
        try {
            coroutineScope {
                val censusJob = censusEngine?.let { c -> async { c.run() } }
                // All adapters listen at once, so a scan takes as long as the slowest one, not the sum.
                adapters.map { a -> launch { a.discover().collect { found -> synchronized(this@LanHome) { add(a, found) } } } }.forEach { it.join() }
                when (val r = censusJob?.await()) {
                    is PatchbayResult.Ok -> _census.value = r.value
                    is PatchbayResult.Err -> report(r.error.message)
                    null -> {}
                }
            }
        } catch (e: Exception) {
            // Adapters return errors as values; this only catches programming errors, and reports them.
            _scan.value = ScanState.FAILED
            report("scan failed: ${e.javaClass.simpleName}")
            return PatchbayResult.Err(PatchbayError.ProtocolViolation("scan failed"))
        }
        _scan.value = ScanState.DONE
        log?.info("home", "scan finished: ${_devices.value.size} managed, ${_census.value.size} answered the census")
        return PatchbayResult.Ok(_devices.value.size)
    }

    private fun add(adapter: DeviceAdapter, found: DiscoveredDevice) {
        val id = adapter.deviceId(found)
        val existing = _devices.value.firstOrNull { it.device.id == id }
        if (existing == null) {
            val sameModel = _devices.value.count { it.device.model == found.model }
            val device = Device(
                id = id,
                name = listOfNotNull(found.vendor, found.model).joinToString(" ") + if (sameModel > 0) " ${sameModel + 1}" else "",
                kind = found.kind, vendor = found.vendor, model = found.model, roomId = null, placement = null,
                controlPath = found.controlPath, capabilities = adapter.capabilities(id), adapter = adapter.id,
            )
            val unpaired = adapter.requiresPairing && store?.pairing(id) == null
            if (unpaired) pendingPairing[id] = found
            track(adapter, device, found.address, observe = !unpaired)
            log?.info("home", "found ${device.name} at ${found.address}" + if (unpaired) " (needs pairing)" else "")
        } else if (existing.address != found.address) {
            _devices.update { list -> list.map { if (it.device.id == id) it.copy(address = found.address) else it } }
        }
        val d = (existing?.device ?: _devices.value.first { it.device.id == id }.device)
        store?.saveDevice(
            StoredDevice(
                d.id, adapter.id.value, found.key, d.name, d.kind, d.vendor, d.model, found.address, found.port,
                d.controlPath, d.roomId, 0, 0,
            ),
            now = clock(),
        )
    }

    private fun updateSnapshot(snap: DeviceSnapshot) {
        // Capabilities can grow once a device is connected (e.g. a TV's input list), so refresh them too.
        val caps = owners[snap.deviceId]?.capabilities(snap.deviceId)
        _devices.update { list ->
            list.map {
                if (it.device.id != snap.deviceId) it
                else it.copy(snapshot = merge(it.snapshot, snap), device = if (caps != null && caps != it.device.capabilities) it.device.copy(capabilities = caps) else it.device)
            }
        }
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
