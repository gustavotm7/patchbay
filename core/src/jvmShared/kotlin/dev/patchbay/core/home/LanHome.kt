package dev.patchbay.core.home

import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.Pairing
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.discovery.NetworkCensus
import dev.patchbay.core.log.SessionLog
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.CommandValidator
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * [LiveHome] over real adapters. One instance per process, created by Bootstrap.
 *
 * This class owns the list of devices and how devices come and go: scanning, adding by address, pairing, forgetting, and the observers that keep
 * each device's state current. Everything else the home does is a separate part it wires together (they share a [HomeContext]): the activity list,
 * scenes, automations, adaptive lighting, away mode, notifications, what the devices' reports mean for the owner, self-healing rediscovery,
 * preferences and the diagnostics report.
 */
public class LanHome(
    private val netGuard: NetGuard,
    private val adapters: List<DeviceAdapter>,
    private val scope: CoroutineScope,
    private val log: SessionLog?,
    private val store: HomeStore? = null,
    private val censusEngine: NetworkCensus? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) : LiveHome {

    // Everything below is declared before `init` runs: restoring saved devices starts observers that use all of it at once.

    private val _devices = MutableStateFlow<List<LiveDevice>>(emptyList())
    override val devices: StateFlow<List<LiveDevice>> = _devices.asStateFlow()
    private val _scan = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scan.asStateFlow()
    override val netStats: StateFlow<NetGuardStats> get() = netGuard.stats
    private val _census = MutableStateFlow<List<Finding>>(emptyList())
    override val census: StateFlow<List<Finding>> = _census.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _report = MutableStateFlow<List<AdapterScan>>(emptyList())
    override val scanReport: StateFlow<List<AdapterScan>> = _report.asStateFlow()
    private val _rooms = MutableStateFlow<List<String>>(emptyList())
    override val rooms: StateFlow<List<String>> = _rooms.asStateFlow()

    private val owners = ConcurrentHashMap<DeviceId, DeviceAdapter>()
    private val observers = ConcurrentHashMap<DeviceId, Job>()
    private val pendingPairing = ConcurrentHashMap<DeviceId, DiscoveredDevice>()

    /** What each paired device's adapter was last given, kept so a device that moves can be re-registered at its new address without losing its secret or pinned key. */
    private val pairings = ConcurrentHashMap<DeviceId, Pairing>()

    private val ctx = HomeContext(scope, clock, zone, random, store, log, devices)
    private val activityLog = ActivityLog(ctx)
    private val notifier = Notifier(ctx)
    private val prefs = HomePreferences(ctx)
    private val sceneBook = Scenes(ctx, activityLog) { id, command -> send(id, command) }
    private val schedule = Automations(ctx, sceneBook, activityLog, prefs.location) { id -> runScene(id) }
    private val adaptiveLights = AdaptiveLighting(ctx, activityLog) { id, command -> send(id, command) }
    private val awayMode = AwayMode(ctx, activityLog, { id, command -> send(id, command) }) { allOff() }
    private val watcher = DeviceWatcher(ctx, activityLog, notifier) { message -> report(message) }
    private val healer = SelfHeal(ctx, adapters, { owners[it] }, { _scan.value == ScanState.SCANNING }) { adapter, found -> synchronized(this) { add(adapter, found) } }
    private val diagnostics = DiagnosticsCollector(
        ctx, adapters, { owners[it] }, healer, { netGuard.stats.value }, { _report.value }, { _census.value }, activityLog,
        sceneBook.scenes, schedule.automations, _rooms, { maxLines -> recentLog(maxLines) },
    )

    // What the parts publish, as the interface asks for it.
    override val activity: StateFlow<List<LedgerEntry>> = activityLog.entries
    override fun timeLabel(epochMillis: Long): String = activityLog.timeLabel(epochMillis)
    override fun activityCsv(): String = activityLog.csv()
    override val trends: dev.patchbay.core.model.TrendSource = watcher.trends
    override val quietHours: StateFlow<QuietHours?> = notifier.quietHours
    override suspend fun setQuietHours(quiet: QuietHours?): Unit = notifier.setQuietHours(quiet)
    override val notifications: Flow<String> = notifier.messages
    override val location: StateFlow<HomeLocation?> = prefs.location
    override suspend fun setLocation(latitude: Double, longitude: Double): PatchbayResult<Unit> = prefs.setLocation(latitude, longitude)
    override val favourites: StateFlow<Set<DeviceId>> = prefs.favourites
    override suspend fun setFavourite(device: DeviceId, on: Boolean): Unit = prefs.setFavourite(device, on)
    override val scenes: StateFlow<List<Scene>> = sceneBook.scenes
    override suspend fun saveScene(name: String, devices: List<DeviceId>): PatchbayResult<Unit> = sceneBook.save(name, devices)
    override suspend fun runScene(id: String): PatchbayResult<Unit> = sceneBook.run(id)
    override suspend fun deleteScene(id: String): Unit = sceneBook.delete(id)
    override val automations: StateFlow<List<Automation>> = schedule.automations
    override suspend fun saveAutomation(name: String, sceneId: String, hour: Int, minute: Int, days: Set<Int>, sun: String?, offsetMinutes: Int): PatchbayResult<Unit> =
        schedule.save(name, sceneId, hour, minute, days, sun, offsetMinutes)
    override suspend fun setAutomationEnabled(id: String, enabled: Boolean): Unit = schedule.setEnabled(id, enabled)
    override suspend fun deleteAutomation(id: String): Unit = schedule.delete(id)
    override val adaptive: StateFlow<Set<DeviceId>> = adaptiveLights.adaptive
    override suspend fun setAdaptive(device: DeviceId, on: Boolean): Unit = adaptiveLights.set(device, on)
    override val away: StateFlow<AwayState> = awayMode.away
    override suspend fun setAway(active: Boolean, simulate: Set<DeviceId>): Unit = awayMode.set(active, simulate)
    override fun diagnosticsReport(header: List<String>): String = diagnostics.report(header)

    override suspend fun createBackup(passphrase: String): PatchbayResult<String> {
        val s = store ?: return PatchbayResult.Err(PatchbayError.Unsupported("Nothing is saved on this device, so there is nothing to back up"))
        val chars = passphrase.toCharArray()
        return try {
            withContext(Dispatchers.Default) { dev.patchbay.core.backup.Backup.create(s, chars, clock()) }
        } finally {
            chars.fill('\u0000')
        }
    }

    override suspend fun restoreBackup(text: String, passphrase: String): PatchbayResult<String> {
        val s = store ?: return PatchbayResult.Err(PatchbayError.Unsupported("Nothing is saved on this device, so there is nowhere to restore to"))
        val chars = passphrase.toCharArray()
        val r = try {
            withContext(Dispatchers.Default) { dev.patchbay.core.backup.Backup.restore(s, text, chars, clock()) }
        } finally {
            chars.fill('\u0000')
        }
        return when (r) {
            is PatchbayResult.Err -> r
            is PatchbayResult.Ok -> {
                log?.info("home", "restored a backup: ${r.value.devices} devices, ${r.value.pairings} pairings")
                report("Backup restored. Close Patchbay and open it again to use it.")
                PatchbayResult.Ok("Restored ${r.value.devices} devices and ${r.value.pairings} pairings. Close Patchbay and open it again to use them.")
            }
        }
    }

    /**
     * Each of these runs one round of its job: automations whose minute it is, adaptive colour, away-mode presence, and a quiet search for devices that
     * stopped answering. They run by themselves every 15 seconds; public so tests can drive them with a fixed clock.
     */
    public fun tickAutomations(nowMillis: Long = clock()): Unit = schedule.tick(nowMillis)
    public fun tickAdaptive(nowMillis: Long = clock()): Unit = adaptiveLights.tick(nowMillis)
    public fun tickAway(nowMillis: Long = clock()): Unit = awayMode.tick(nowMillis)
    public suspend fun tickHeal(nowMillis: Long = clock()): Unit = healer.tick(nowMillis)

    init {
        sceneBook.onDeleted = { schedule.publish() }
        sceneBook.load()
        activityLog.load()
        schedule.load()
        adaptiveLights.load()
        awayMode.load()
        notifier.load()
        prefs.load()
        watcher.loadTrends()
        restoreSaved()
        refreshRooms()
        scope.launch {
            while (true) {
                delay(15_000)
                tickAutomations()
                tickAdaptive()
                tickAway()
            }
        }
        scope.launch {
            while (true) {
                delay(15_000)
                try { tickHeal() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log?.warn("home", "quiet rescan failed: ${e.javaClass.simpleName}") }
            }
        }
    }

    override fun dismissProblem() {
        _problem.value = null
    }

    override fun recentLog(maxLines: Int): String {
        val f = log?.file?.takeIf { it.exists() } ?: return "No log yet."
        return f.readLines().takeLast(maxLines.coerceIn(1, 2_000)).joinToString("\n")
    }

    internal fun reportStartupProblem(message: String): Unit = report(message)

    private fun report(message: String) {
        _problem.value = message
        log?.warn("home", message)
    }

    // ---- devices: coming, going and staying current

    /** Devices saved by earlier sessions appear immediately and are polled; no scan needed. */
    private fun restoreSaved() {
        val saved = store?.devices() ?: return
        for (sd in saved) {
            val adapter = adapters.firstOrNull { it.id.value == sd.adapter } ?: continue
            val pairing = store.pairing(sd.id)?.let { Pairing(it.deviceId, it.secret, it.pinnedSha256) }
            if (pairing != null) pairings[sd.id] = pairing
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
            is PatchbayResult.Err -> {
                // Backing out is the user's choice, not a fault: say nothing and leave nothing on screen.
                if (r.error !is PatchbayError.Cancelled) report(r.error.message)
                return r
            }
        }
        store?.savePairing(StoredPairing(device, pairing.secret, pairing.pinnedFingerprint, clock()))
        adapter.restore(device, found.address, pairing)
        pairings[device] = pairing
        pendingPairing.remove(device)
        _devices.update { list -> list.map { if (it.device.id == device) it.copy(needsPairing = false) else it } }
        log?.info("home", "paired ${found.displayName ?: found.model ?: "device"} (key pinned)")
        startObserving(adapter, device)
        return PatchbayResult.Ok(Unit)
    }

    override val manualAdd: List<dev.patchbay.core.adapter.ManualAdd> = adapters.mapNotNull { it.manualAdd }

    override suspend fun addManually(adapter: dev.patchbay.core.model.AdapterId, address: String, key: String, choice: String?): PatchbayResult<DeviceId> {
        val a = adapters.firstOrNull { it.id == adapter } ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("unknown system"))
        val first = when (val r = a.manualDevice(address.trim(), key.trim(), choice)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { log?.warn("home", "add by address failed: ${r.error.message}"); return r }
        }
        val found = when (val r = a.resolveManual(first)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> { log?.warn("home", "add by address failed: ${r.error.message}"); return r }
        }
        synchronized(this) { add(a, found) }
        log?.info("home", "added ${found.vendor ?: "device"} by address ${found.address}")
        return PatchbayResult.Ok(a.deviceId(found))
    }

    override suspend fun streamLease(device: DeviceId): PatchbayResult<dev.patchbay.core.adapter.StreamLease> {
        val provider = owners[device] as? dev.patchbay.core.adapter.StreamProvider
            ?: return PatchbayResult.Err(PatchbayError.Unsupported("this device has no stream"))
        if (pendingPairing.containsKey(device)) return PatchbayResult.Err(PatchbayError.Unauthorized("pair this device first"))
        return provider.streamLease(device).also { if (it is PatchbayResult.Ok) log?.info("home", "stream opened for a camera") }
    }

    override suspend fun forget(device: DeviceId) {
        val name = _devices.value.firstOrNull { it.device.id == device }?.device?.name ?: return
        observers.remove(device)?.cancel()
        try { owners[device]?.unpair(device) } catch (e: Exception) { log?.warn("home", "could not ask a device to forget us: ${e.javaClass.simpleName}") }
        watcher.forget(device)
        healer.forget(device)
        pairings.remove(device)
        owners.remove(device); pendingPairing.remove(device)
        synchronized(this) {
            _devices.update { list -> list.filterNot { it.device.id == device } }
            refreshRooms()
        }
        store?.forget(device)
        activityLog.record(name, "Removed from Patchbay")
        log?.info("home", "removed a device and its saved pairing")
    }

    override suspend fun allOff(): PatchbayResult<Int> {
        val targets = _devices.value.filter {
            val snap = it.snapshot
            (it.device.kind == DeviceKind.LIGHT || it.device.kind == DeviceKind.PLUG) &&
                snap != null && snap.reachability == Reachability.ONLINE &&
                snap.state<CapabilityState.PowerState>()?.on == true
        }
        var failed = 0
        for (t in targets) {
            if (send(t.device.id, Command.SetPower(false)) is PatchbayResult.Err) failed++
            delay(40)
        }
        activityLog.record("All lights and plugs", "Switched off from Patchbay (${targets.size - failed} of ${targets.size})")
        return if (failed == 0) PatchbayResult.Ok(targets.size)
        else PatchbayResult.Err(PatchbayError.Unreachable("$failed of ${targets.size} didn't respond"))
    }

    private fun refreshRooms() {
        _rooms.value = _devices.value.mapNotNull { it.device.roomId?.value }.distinct().sortedBy { it.lowercase() }
    }

    override suspend fun assignRoom(device: DeviceId, room: String?) {
        val name = room?.trim()?.replace(Regex("\\s+"), " ")
        if (room != null && (name.isNullOrEmpty() || name.length > 24 || !name.all { it.isLetterOrDigit() || it == ' ' || it == '-' })) return
        val roomId = name?.let { dev.patchbay.core.model.RoomId(it) }
        synchronized(this) {
            _devices.update { list -> list.map { if (it.device.id == device) it.copy(device = it.device.copy(roomId = roomId)) else it } }
            refreshRooms()
        }
        store?.setRoom(device, roomId)
    }

    override suspend fun rename(device: DeviceId, name: String): PatchbayResult<Unit> {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 1..32 || !clean.all { it.isLetterOrDigit() || it == ' ' || it == '-' }) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("Use a short name with letters, numbers, spaces or hyphens"))
        }
        val old = _devices.value.firstOrNull { it.device.id == device }?.device?.name ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("unknown device"))
        synchronized(this) {
            _devices.update { list -> list.map { if (it.device.id == device) it.copy(device = it.device.copy(name = clean)) else it } }
        }
        store?.devices()?.firstOrNull { it.id == device }?.let { store.saveDevice(it.copy(name = clean), clock()) }
        if (old != clean) activityLog.record(clean, "Renamed (was $old)")
        return PatchbayResult.Ok(Unit)
    }

    // ---- finding devices

    override suspend fun scan(): PatchbayResult<Int> {
        _scan.value = ScanState.SCANNING
        try {
            coroutineScope {
                val censusJob = censusEngine?.let { c -> async { c.run() } }
                // All adapters listen at once, so a scan takes as long as the slowest one, not the sum.
                val counts = ConcurrentHashMap<dev.patchbay.core.model.AdapterId, Int>()
                adapters.map { a ->
                    launch { a.discover().collect { found -> counts.merge(a.id, 1, Int::plus); synchronized(this@LanHome) { add(a, found) } } }
                }.forEach { it.join() }
                _report.value = adapters.map { a -> AdapterScan(a.systemName, counts[a.id] ?: 0, a.discoveryNote, a.discoverable) }
                when (val r = censusJob?.await()) {
                    is PatchbayResult.Ok -> _census.value = r.value
                    is PatchbayResult.Err -> report(r.error.message)
                    null -> {}
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _scan.value = ScanState.IDLE
            throw e
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

    /** A device found by a scan, a quiet search or an address: new ones are added, a known one at a new address is told about it. Called under this home's lock. */
    private fun add(adapter: DeviceAdapter, found: DiscoveredDevice) {
        val id = adapter.deviceId(found)
        val existing = _devices.value.firstOrNull { it.device.id == id }
        if (existing == null) {
            // The name the device or the owner gave it wins; otherwise "Vendor Model". Duplicates get a number.
            val baseName = found.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: listOfNotNull(found.vendor, found.model).joinToString(" ")
            val sameName = _devices.value.count { it.device.name == baseName || it.device.name.startsWith("$baseName ") && it.device.name.removePrefix("$baseName ").all(Char::isDigit) }
            val device = Device(
                id = id,
                name = baseName + if (sameName > 0) " ${sameName + 1}" else "",
                kind = found.kind, vendor = found.vendor, model = found.model, roomId = null, placement = null,
                controlPath = found.controlPath, capabilities = adapter.capabilities(id), adapter = adapter.id,
            )
            val unpaired = adapter.requiresPairing && store?.pairing(id) == null
            if (unpaired) pendingPairing[id] = found
            track(adapter, device, found.address, observe = !unpaired)
            log?.info("home", "found ${device.name} at ${found.address}" + if (unpaired) " (needs pairing)" else "")
        } else if (existing.address != found.address) {
            _devices.update { list -> list.map { if (it.device.id == id) it.copy(address = found.address) else it } }
            // The device moved (a DHCP change, a router reboot): tell its adapter, or it keeps knocking on the old address. The pairing
            // and pinned key travel with it; an unpaired device that needs pairing has nothing to re-register yet.
            val kept = pairings[id]
            if (!adapter.requiresPairing || kept != null) {
                adapter.relocate(id, found, kept)
                activityLog.record(existing.device.name, "Found again at a new address")
                log?.info("home", "${existing.device.name} moved to ${found.address}")
            }
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

    // ---- what a device reports, and commands to it

    private fun updateSnapshot(snap: DeviceSnapshot) {
        // Capabilities can grow once a device is connected (e.g. a TV's input list), so refresh them too.
        val caps = owners[snap.deviceId]?.capabilities(snap.deviceId)
        val trust = owners[snap.deviceId]?.trustChanged(snap.deviceId) == true
        _devices.update { list ->
            list.map {
                if (it.device.id != snap.deviceId) it
                else it.copy(
                    snapshot = merge(it.snapshot, snap), trustChanged = trust,
                    device = if (caps != null && caps != it.device.capabilities) it.device.copy(capabilities = caps) else it.device,
                )
            }
        }
        healer.noteReachability(snap.deviceId, snap.reachability)
        watcher.onSnapshot(snap, trust)
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
        watcher.commandSent(device)
        val r = adapter.command(device, command)
        if (r is PatchbayResult.Ok) optimistic(device, command)
        else report((r as PatchbayResult.Err).error.message)
        return r
    }

    /** Shows the commanded value immediately; the next poll confirms or corrects it. */
    private fun optimistic(device: DeviceId, command: Command) {
        _devices.update { list ->
            list.map {
                if (it.device.id != device) it else {
                    val s = it.snapshot ?: DeviceSnapshot(device, Reachability.ONLINE, emptyList(), EpochMillis(clock()))
                    it.copy(snapshot = s.copy(states = optimisticStates(s.states, command)))
                }
            }
        }
    }
}
