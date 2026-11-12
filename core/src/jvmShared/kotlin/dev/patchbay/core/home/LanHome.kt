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
import kotlinx.coroutines.delay
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
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) : LiveHome {

    private val _devices = MutableStateFlow<List<LiveDevice>>(emptyList())
    override val devices: StateFlow<List<LiveDevice>> = _devices.asStateFlow()
    private val _scan = MutableStateFlow(ScanState.IDLE)
    override val scanState: StateFlow<ScanState> = _scan.asStateFlow()
    override val netStats: StateFlow<NetGuardStats> get() = netGuard.stats
    private val _census = MutableStateFlow<List<Finding>>(emptyList())
    override val census: StateFlow<List<Finding>> = _census.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    private val _report = MutableStateFlow<List<dev.patchbay.core.home.AdapterScan>>(emptyList())
    private val _rooms = MutableStateFlow<List<String>>(emptyList())
    private val _scenes = MutableStateFlow<List<Scene>>(emptyList())
    private var storedScenes: List<StoredScene> = emptyList()
    private val _automations = MutableStateFlow<List<Automation>>(emptyList())
    private var storedAutomations: List<StoredAutomation> = emptyList()
    private val lastAutomationRun = ConcurrentHashMap<String, Long>()
    private val _adaptive = MutableStateFlow<Set<DeviceId>>(emptySet())
    private val adaptiveApplied = ConcurrentHashMap<DeviceId, Int>()
    private val adaptiveAppliedAt = ConcurrentHashMap<DeviceId, Long>()
    private val adaptiveOverridden = ConcurrentHashMap.newKeySet<DeviceId>()
    private val _away = MutableStateFlow(AwayState())
    private val _location = MutableStateFlow<HomeLocation?>(null)
    private val _favourites = MutableStateFlow<Set<DeviceId>>(emptySet())
    private val _notifications = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    private val lastNotified = ConcurrentHashMap<String, Long>()
    private val _quiet = MutableStateFlow<QuietHours?>(null)
    override val quietHours: StateFlow<QuietHours?> = _quiet.asStateFlow()
    private val printStartedAt = ConcurrentHashMap<DeviceId, Long>()
    private val nextFlip = ConcurrentHashMap<DeviceId, Long>()
    @Volatile private var simOffDay = -1L
    private val _activity = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private data class Summary(
        val reach: dev.patchbay.core.model.Reachability,
        val power: Boolean?,
        val stage: dev.patchbay.core.model.PrintStage?,
        val media: dev.patchbay.core.model.PlaybackStatus?,
    )
    private val lastSummary = ConcurrentHashMap<DeviceId, Summary>()
    private val lastCommandAt = ConcurrentHashMap<DeviceId, Long>()
    private val trustAlerted = ConcurrentHashMap.newKeySet<DeviceId>()
    @Volatile private var ledgerFlush: Job? = null
    private val sceneJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    override val problem: StateFlow<String?> = _problem.asStateFlow()
    override val scanReport: StateFlow<List<dev.patchbay.core.home.AdapterScan>> = _report.asStateFlow()

    override val activity: StateFlow<List<LedgerEntry>> = _activity.asStateFlow()

    override fun timeLabel(epochMillis: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val t = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val hhmm = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) {
            today -> "Today $hhmm"
            today.minusDays(1) -> "Yesterday $hhmm"
            else -> "${t.dayOfMonth} ${t.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} $hhmm"
        }
    }

    override fun activityCsv(): String = LedgerCsv.of(_activity.value) { java.time.Instant.ofEpochMilli(it).toString() }

    private fun loadLedger() {
        val raw = store?.setting("ledger") ?: return
        _activity.value = try {
            sceneJson.decodeFromString<List<StoredLedgerEntry>>(raw).map { LedgerEntry(it.t, it.d, it.e) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Adds an entry (newest first, at most 300) and schedules a write; writes are batched so a burst costs one. */
    private fun record(deviceName: String, event: String) {
        _activity.update { (listOf(LedgerEntry(clock(), deviceName, event)) + it).take(300) }
        if (store != null && ledgerFlush == null) {
            ledgerFlush = scope.launch {
                delay(2_000)
                ledgerFlush = null
                store.putSetting("ledger", sceneJson.encodeToString(_activity.value.map { StoredLedgerEntry(it.atMillis, it.deviceName, it.event) }))
            }
        }
    }

    override suspend fun forget(device: DeviceId) {
        val name = _devices.value.firstOrNull { it.device.id == device }?.device?.name ?: return
        observers.remove(device)?.cancel()
        try { owners[device]?.unpair(device) } catch (e: Exception) { log?.warn("home", "could not ask a device to forget us: ${e.javaClass.simpleName}") }
        owners.remove(device); pendingPairing.remove(device); lastSummary.remove(device); trustAlerted.remove(device)
        synchronized(this) {
            _devices.update { list -> list.filterNot { it.device.id == device } }
            refreshRooms()
        }
        store?.forget(device)
        record(name, "Removed from Patchbay")
        log?.info("home", "removed a device and its saved pairing")
    }

    override suspend fun allOff(): PatchbayResult<Int> {
        val targets = _devices.value.filter {
            val snap = it.snapshot
            (it.device.kind == dev.patchbay.core.model.DeviceKind.LIGHT || it.device.kind == dev.patchbay.core.model.DeviceKind.PLUG) &&
                snap != null && snap.reachability == dev.patchbay.core.model.Reachability.ONLINE &&
                snap.state<dev.patchbay.core.model.CapabilityState.PowerState>()?.on == true
        }
        var failed = 0
        for (t in targets) {
            if (send(t.device.id, Command.SetPower(false)) is PatchbayResult.Err) failed++
            delay(40)
        }
        record("All lights and plugs", "Switched off from Patchbay (${targets.size - failed} of ${targets.size})")
        return if (failed == 0) PatchbayResult.Ok(targets.size)
        else PatchbayResult.Err(PatchbayError.Unreachable("$failed of ${targets.size} didn't respond"))
    }

    override val automations: StateFlow<List<Automation>> = _automations.asStateFlow()

    private fun loadAutomations() {
        val raw = store?.setting("automations") ?: return
        storedAutomations = try { sceneJson.decodeFromString(raw) } catch (e: Exception) { emptyList() }
        publishAutomations()
    }

    private fun publishAutomations() {
        _automations.value = storedAutomations.map { a ->
            Automation(a.id, a.name, a.sceneId, storedScenes.firstOrNull { it.id == a.sceneId }?.name, a.hour, a.minute, a.days.toSet(), a.enabled, a.sun, a.offset)
        }
    }

    private fun persistAutomations() {
        store?.putSetting("automations", sceneJson.encodeToString(storedAutomations))
        publishAutomations()
    }

    override suspend fun saveAutomation(name: String, sceneId: String, hour: Int, minute: Int, days: Set<Int>, sun: String?, offsetMinutes: Int): PatchbayResult<Unit> {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 1..32 || !clean.all { it.isLetterOrDigit() || it == ' ' || it == '-' }) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("Give the automation a short name using letters, numbers, spaces or hyphens"))
        }
        if (sun != null) {
            if (sun != "sunrise" && sun != "sunset") return PatchbayResult.Err(PatchbayError.InvalidArgument("Choose sunrise or sunset"))
            if (offsetMinutes !in -180..180) return PatchbayResult.Err(PatchbayError.InvalidArgument("The offset can be up to 3 hours before or after"))
            if (_location.value == null) return PatchbayResult.Err(PatchbayError.InvalidArgument("Set your home location in Settings first"))
        } else if (hour !in 0..23 || minute !in 0..59) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("The time must be between 00:00 and 23:59"))
        }
        if (days.isEmpty() || days.any { it !in 1..7 }) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick at least one day"))
        if (storedScenes.none { it.id == sceneId }) return PatchbayResult.Err(PatchbayError.InvalidArgument("Pick a scene to run"))
        synchronized(this) {
            storedAutomations = storedAutomations + StoredAutomation(
                java.util.UUID.randomUUID().toString().take(8), clean, sceneId, if (sun == null) hour else 0, if (sun == null) minute else 0,
                days.sorted(), true, sun, if (sun == null) 0 else offsetMinutes,
            )
            persistAutomations()
        }
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun setAutomationEnabled(id: String, enabled: Boolean) {
        synchronized(this) {
            storedAutomations = storedAutomations.map { if (it.id == id) it.copy(enabled = enabled) else it }
            persistAutomations()
        }
    }

    override suspend fun deleteAutomation(id: String) {
        synchronized(this) {
            storedAutomations = storedAutomations.filterNot { it.id == id }
            persistAutomations()
        }
    }

    /**
     * Runs every enabled automation whose time is this minute on a matching day, once per minute. Called every 15 seconds
     * while Patchbay runs; public so tests can drive it with a fixed clock.
     */
    public fun tickAutomations(nowMillis: Long = clock()) {
        val t = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val minuteKey = t.toLocalDate().toEpochDay() * 1_440 + t.hour * 60 + t.minute
        for (a in storedAutomations) {
            if (!a.enabled || t.dayOfWeek.value !in a.days) continue
            val sunName = a.sun
            if (sunName == null) {
                if (t.hour != a.hour || t.minute != a.minute) continue
            } else {
                val loc = _location.value ?: continue
                val (rise, set) = SunTimes.compute(loc.latitude, loc.longitude, t.dayOfYear) ?: continue
                val minutes = if (sunName == "sunrise") rise else set
                val instant = t.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds((minutes * 60).toLong() + a.offset * 60L)
                val local = instant.atZone(zone)
                if (local.toLocalDate() != t.toLocalDate() || local.hour != t.hour || local.minute != t.minute) continue
            }
            if (lastAutomationRun[a.id] == minuteKey) continue
            lastAutomationRun[a.id] = minuteKey
            val scene = storedScenes.firstOrNull { it.id == a.sceneId }
            if (scene == null) {
                record("Automation: ${a.name}", "Skipped: its scene was deleted")
                continue
            }
            record("Automation: ${a.name}", "Running scene ${scene.name}")
            scope.launch { runScene(scene.id) }
        }
    }

    override val adaptive: StateFlow<Set<DeviceId>> = _adaptive.asStateFlow()
    override val away: StateFlow<AwayState> = _away.asStateFlow()

    private fun loadAdaptiveAndAway() {
        store?.setting("adaptive")?.let { raw ->
            _adaptive.value = try { sceneJson.decodeFromString<List<String>>(raw).map(::DeviceId).toSet() } catch (e: Exception) { emptySet() }
        }
        store?.setting("away")?.let { raw ->
            _away.value = try { sceneJson.decodeFromString<StoredAway>(raw).let { AwayState(it.active, it.simulate.map(::DeviceId).toSet()) } } catch (e: Exception) { AwayState() }
        }
    }

    override suspend fun setAdaptive(device: DeviceId, on: Boolean) {
        _adaptive.update { if (on) it + device else it - device }
        adaptiveApplied.remove(device); adaptiveOverridden.remove(device)
        store?.putSetting("adaptive", sceneJson.encodeToString(_adaptive.value.map { it.value }))
        val name = _devices.value.firstOrNull { it.device.id == device }?.device?.name ?: return
        record(name, if (on) "Adaptive lighting turned on" else "Adaptive lighting turned off")
    }

    /**
     * Moves the white colour of each adaptive light that is on toward the day's curve. A light you change yourself is left alone
     * until you switch it off and on again. Colour-mode lights are never touched. Public so tests can drive it with a fixed clock.
     */
    public fun tickAdaptive(nowMillis: Long = clock()) {
        val t = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val minute = t.hour * 60 + t.minute
        for (id in _adaptive.value) {
            val live = _devices.value.firstOrNull { it.device.id == id } ?: continue
            val snap = live.snapshot ?: continue
            if (snap.reachability != dev.patchbay.core.model.Reachability.ONLINE) continue
            if (snap.state<dev.patchbay.core.model.CapabilityState.PowerState>()?.on != true) {
                adaptiveApplied.remove(id); adaptiveOverridden.remove(id); continue
            }
            val k = snap.state<dev.patchbay.core.model.CapabilityState.ColourTemperatureState>()?.kelvin?.value ?: continue
            val last = adaptiveApplied[id]
            if (last != null && nowMillis - (adaptiveAppliedAt[id] ?: 0L) > 20_000 && kotlin.math.abs(k - last) > 250) adaptiveOverridden += id
            if (id in adaptiveOverridden) continue
            val cap = live.device.capability<dev.patchbay.core.model.Capability.ColourTemperature>() ?: continue
            val target = AdaptiveCurve.kelvinAt(minute).coerceIn(cap.warmest.value, cap.coolest.value)
            if (kotlin.math.abs(k - target) > 100) {
                adaptiveApplied[id] = target; adaptiveAppliedAt[id] = nowMillis
                scope.launch { send(id, Command.SetColourTemperature(dev.patchbay.core.model.Kelvin(target))) }
            }
        }
    }

    override suspend fun setAway(active: Boolean, simulate: Set<DeviceId>) {
        val was = _away.value.active
        _away.value = AwayState(active, if (active) simulate else emptySet())
        store?.putSetting("away", sceneJson.encodeToString(StoredAway(active, _away.value.simulate.map { it.value })))
        nextFlip.clear()
        if (active && !was) {
            record("Away mode", "Turned on" + if (simulate.isNotEmpty()) " (simulating presence with ${simulate.size} lights)" else "")
            allOff()
        } else if (!active && was) {
            record("Away mode", "Turned off")
        }
    }

    /**
     * While away with simulation on: between 18:00 and 22:59 each chosen light flips at irregular intervals (20 to 60 minutes),
     * and at 23:00 they all go off once. Public so tests can drive it with a fixed clock and a seeded random.
     */
    public fun tickAway(nowMillis: Long = clock()) {
        val a = _away.value
        if (!a.active || a.simulate.isEmpty()) return
        val t = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        when {
            t.hour in 18..22 -> for (id in a.simulate) {
                val live = _devices.value.firstOrNull { it.device.id == id } ?: continue
                val snap = live.snapshot ?: continue
                if (snap.reachability != dev.patchbay.core.model.Reachability.ONLINE) continue
                val due = nextFlip.getOrPut(id) { nowMillis + random.nextInt(1, 40) * 60_000L }
                if (nowMillis < due) continue
                nextFlip[id] = nowMillis + random.nextInt(20, 61) * 60_000L
                val on = snap.state<dev.patchbay.core.model.CapabilityState.PowerState>()?.on == true
                record("Away mode", "Simulated presence: ${live.device.name} ${if (on) "off" else "on"}")
                scope.launch { send(id, Command.SetPower(!on)) }
            }
            t.hour == 23 && simOffDay != t.toLocalDate().toEpochDay() -> {
                simOffDay = t.toLocalDate().toEpochDay()
                nextFlip.clear()
                for (id in a.simulate) scope.launch { send(id, Command.SetPower(false)) }
                record("Away mode", "Simulated presence ended for the night")
            }
            t.hour < 18 -> nextFlip.clear()
        }
    }

    override val location: StateFlow<HomeLocation?> = _location.asStateFlow()
    override val favourites: StateFlow<Set<DeviceId>> = _favourites.asStateFlow()
    override val notifications: kotlinx.coroutines.flow.Flow<String> = _notifications

    private fun loadLocationAndFavourites() {
        store?.setting("quiet")?.let { _quiet.value = QuietHours.parse(it) }
        store?.setting("location")?.let { raw ->
            _location.value = try { sceneJson.decodeFromString<StoredLocation>(raw).let { HomeLocation(it.lat, it.lon) } } catch (e: Exception) { null }
        }
        store?.setting("favourites")?.let { raw ->
            _favourites.value = try { sceneJson.decodeFromString<List<String>>(raw).map(::DeviceId).toSet() } catch (e: Exception) { emptySet() }
        }
    }

    override suspend fun setLocation(latitude: Double, longitude: Double): PatchbayResult<Unit> {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return PatchbayResult.Err(PatchbayError.InvalidArgument("Latitude is -90 to 90 and longitude -180 to 180"))
        _location.value = HomeLocation(latitude, longitude)
        store?.putSetting("location", sceneJson.encodeToString(StoredLocation(latitude, longitude)))
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun setFavourite(device: DeviceId, on: Boolean) {
        _favourites.update { if (on) it + device else it - device }
        store?.putSetting("favourites", sceneJson.encodeToString(_favourites.value.map { it.value }))
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
        if (old != clean) record(clean, "Renamed (was $old)")
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun setQuietHours(quiet: QuietHours?) {
        _quiet.value = quiet
        store?.putSetting("quiet", quiet?.text() ?: "")
    }

    /**
     * At most one notification per device and kind per hour, so a flapping device can't spam. Outside [urgent] alerts, nothing is sent during quiet
     * hours (it is still in Activity).
     */
    private fun notify(key: String, message: String, windowMs: Long = 3_600_000, urgent: Boolean = false) {
        val now = clock()
        if (!urgent) {
            val q = _quiet.value
            if (q != null) {
                val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
                if (q.contains(t.hour * 60 + t.minute)) return
            }
        }
        val last = lastNotified[key]
        if (last != null && now - last < windowMs) return
        lastNotified[key] = now
        _notifications.tryEmit(message)
    }

    override val rooms: StateFlow<List<String>> = _rooms.asStateFlow()
    override val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()

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

    private fun loadScenes() {
        val raw = store?.setting("scenes") ?: return
        storedScenes = try {
            sceneJson.decodeFromString<List<StoredScene>>(raw)
        } catch (e: Exception) {
            log?.warn("home", "saved scenes could not be read; starting with none")
            emptyList()
        }
        _scenes.value = storedScenes.map { s -> Scene(s.id, s.name, s.steps.map { DeviceId(it.device) }) }
    }

    private fun persistScenes() {
        store?.putSetting("scenes", sceneJson.encodeToString(storedScenes))
        _scenes.value = storedScenes.map { s -> Scene(s.id, s.name, s.steps.map { DeviceId(it.device) }) }
    }

    override suspend fun saveScene(name: String, devices: List<DeviceId>): PatchbayResult<Unit> {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.length !in 1..32 || !clean.all { it.isLetterOrDigit() || it == ' ' || it == '-' }) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("Give the scene a short name using letters, numbers, spaces or hyphens"))
        }
        val steps = devices.mapNotNull { id ->
            val live = _devices.value.firstOrNull { it.device.id == id } ?: return@mapNotNull null
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
            val existing = storedScenes.firstOrNull { it.name.equals(clean, ignoreCase = true) }
            val scene = StoredScene(existing?.id ?: java.util.UUID.randomUUID().toString().take(8), clean, steps)
            storedScenes = storedScenes.filterNot { it.id == scene.id } + scene
            persistScenes()
        }
        log?.info("home", "saved a scene with ${steps.size} devices")
        return PatchbayResult.Ok(Unit)
    }

    override suspend fun runScene(id: String): PatchbayResult<Unit> {
        val scene = storedScenes.firstOrNull { it.id == id } ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("unknown scene"))
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
        record("Scene: ${scene.name}", "Ran (${scene.steps.size - failed} of ${scene.steps.size} devices responded)")
        log?.info("home", "ran a scene: ${scene.steps.size - failed} of ${scene.steps.size} devices responded")
        return if (failed == 0) PatchbayResult.Ok(Unit)
        else PatchbayResult.Err(PatchbayError.Unreachable("$failed of ${scene.steps.size} devices didn't respond"))
    }

    override suspend fun deleteScene(id: String) {
        synchronized(this) {
            storedScenes = storedScenes.filterNot { it.id == id }
            persistScenes()
            publishAutomations()
        }
    }

    override fun dismissProblem() {
        _problem.value = null
    }

    override fun recentLog(maxLines: Int): String {
        val f = log?.file?.takeIf { it.exists() } ?: return "No log yet."
        return f.readLines().takeLast(maxLines.coerceIn(1, 2_000)).joinToString("\n")
    }

    private val owners = ConcurrentHashMap<DeviceId, DeviceAdapter>()
    private val observers = ConcurrentHashMap<DeviceId, Job>()

    init {
        restoreSaved()
        refreshRooms()
        loadScenes()
        loadLedger()
        loadAutomations()
        loadAdaptiveAndAway()
        loadLocationAndFavourites()
        scope.launch {
            while (true) {
                delay(15_000)
                tickAutomations()
                tickAdaptive()
                tickAway()
            }
        }
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
            is PatchbayResult.Err -> {
                // Backing out is the user's choice, not a fault: say nothing and leave nothing on screen.
                if (r.error !is PatchbayError.Cancelled) report(r.error.message)
                return r
            }
        }
        store?.savePairing(StoredPairing(device, pairing.secret, pairing.pinnedFingerprint, clock()))
        adapter.restore(device, found.address, pairing)
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

    override suspend fun scan(): PatchbayResult<Int> {
        _scan.value = ScanState.SCANNING
        try {
            coroutineScope {
                val censusJob = censusEngine?.let { c -> async { c.run() } }
                // All adapters listen at once, so a scan takes as long as the slowest one, not the sum.
                val counts = java.util.concurrent.ConcurrentHashMap<dev.patchbay.core.model.AdapterId, Int>()
                adapters.map { a ->
                    launch { a.discover().collect { found -> counts.merge(a.id, 1, Int::plus); synchronized(this@LanHome) { add(a, found) } } }
                }.forEach { it.join() }
                _report.value = adapters.map { a ->
                    dev.patchbay.core.home.AdapterScan(a.systemName, counts[a.id] ?: 0, a.discoveryNote, a.discoverable)
                }
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
        val live = _devices.value.firstOrNull { it.device.id == snap.deviceId } ?: return
        if (trust && trustAlerted.add(snap.deviceId)) {
            record(live.device.name, "Presented a different security key than the one you paired (stopped talking to it)")
            report("${live.device.name} presented a different security key than the one you paired. Open it for details.")
            notify("trust-${snap.deviceId.value}", "${live.device.name} presented a different security key than the one you paired.", urgent = true)
        } else if (!trust) {
            trustAlerted.remove(snap.deviceId)
        }
        val s = live.snapshot ?: return
        val now = Summary(s.reachability, s.state<dev.patchbay.core.model.CapabilityState.PowerState>()?.on, s.state<dev.patchbay.core.model.CapabilityState.PrintJobState>()?.stage, s.state<dev.patchbay.core.model.CapabilityState.MediaState>()?.status)
        val before = lastSummary.put(snap.deviceId, now)
        if (now.stage == dev.patchbay.core.model.PrintStage.PRINTING && before?.stage != dev.patchbay.core.model.PrintStage.PRINTING) printStartedAt.putIfAbsent(snap.deviceId, clock())
        if (before != null && before.stage != null && before.stage != now.stage &&
            (now.stage == dev.patchbay.core.model.PrintStage.FINISHED || now.stage == dev.patchbay.core.model.PrintStage.FAILED) &&
            (before.stage == dev.patchbay.core.model.PrintStage.PRINTING || before.stage == dev.patchbay.core.model.PrintStage.PREPARING || before.stage == dev.patchbay.core.model.PrintStage.PAUSED)
        ) {
            val took = printStartedAt[snap.deviceId]?.let { " after " + durationText(clock() - it) } ?: ""
            notify("print-${snap.deviceId.value}", "${live.device.name}: print ${if (now.stage == dev.patchbay.core.model.PrintStage.FAILED) "failed" else "finished"}$took.", windowMs = 60_000)
        }
        if (before != null && before != now) describeChange(before, now, snap.deviceId)?.let { record(live.device.name, it) }
        if (now.stage != dev.patchbay.core.model.PrintStage.PRINTING && now.stage != dev.patchbay.core.model.PrintStage.PAUSED && now.stage != dev.patchbay.core.model.PrintStage.PREPARING) printStartedAt.remove(snap.deviceId)
        if (before != null && before.reach != dev.patchbay.core.model.Reachability.OFFLINE && now.reach == dev.patchbay.core.model.Reachability.OFFLINE) {
            notify("offline-${snap.deviceId.value}", "${live.device.name} stopped answering.")
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
        if (a.reach != dev.patchbay.core.model.Reachability.OFFLINE && b.reach == dev.patchbay.core.model.Reachability.OFFLINE) parts += "Went offline"
        if (a.reach == dev.patchbay.core.model.Reachability.OFFLINE && b.reach == dev.patchbay.core.model.Reachability.ONLINE) parts += "Back online"
        if (a.power != null && b.power != null && a.power != b.power) { parts += if (b.power) "Turned on" else "Turned off"; stateChange = true }
        if (b.stage != null && a.stage != b.stage) {
            val took = if (b.stage == dev.patchbay.core.model.PrintStage.FINISHED || b.stage == dev.patchbay.core.model.PrintStage.FAILED) printStartedAt[id]?.let { " after " + durationText(clock() - it) } ?: "" else ""
            parts += "Printer: ${b.stage.name.lowercase()}$took"; stateChange = true
        }
        if (b.media != null && a.media != b.media && b.media != dev.patchbay.core.model.PlaybackStatus.BUFFERING) {
            parts += when (b.media) {
                dev.patchbay.core.model.PlaybackStatus.PLAYING -> "Playing"
                dev.patchbay.core.model.PlaybackStatus.PAUSED -> "Paused"
                else -> "Stopped"
            }
            stateChange = true
        }
        if (parts.isEmpty()) return null
        val cause = if (!stateChange) "" else if (clock() - (lastCommandAt[id] ?: 0L) < 15_000) " (from Patchbay)" else " (at the device or another app)"
        return parts.joinToString("; ") + cause
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
        lastCommandAt[device] = clock()
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
