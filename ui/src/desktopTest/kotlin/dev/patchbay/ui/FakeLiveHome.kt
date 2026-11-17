package dev.patchbay.ui

import dev.patchbay.core.adapter.ManualAdd
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.home.AdapterScan
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.home.LiveHome
import dev.patchbay.core.home.ScanState
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuardStats
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.simulators.DemoHome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** A fixed set of live devices for the tile screens: lights on and off, a plug, a TV, a printer, a camera, an unreachable one, one to pair. */
internal open class FakeLiveHome(private val home: dev.patchbay.core.model.Home, devicesOverride: List<LiveDevice>? = null) : LiveHome {
    private fun live(id: DeviceId, reach: Reachability? = null, pair: Boolean = false, name: String? = null, room: String? = null, trust: Boolean = false, addr: String = "192.168.1.${20 + id.value.length}"): LiveDevice {
        val d = home.device(id)!!.let { if (name != null) it.copy(name = name) else it }.copy(roomId = room?.let { dev.patchbay.core.model.RoomId(it) })
        val snap = home.snapshot(id)?.let { sn -> if (reach != null) sn.copy(reachability = reach, states = emptyList()) else sn }
        return LiveDevice(d, if (pair) null else snap, addr, needsPairing = pair, trustChanged = trust)
    }

    /** The devices shown; a test changes this to play a device's own report arriving. */
    val deviceFlow: MutableStateFlow<List<LiveDevice>> = MutableStateFlow(
        devicesOverride ?: listOf(
            live(DemoHome.Ids.FLOOR_LAMP, name = "Floor lamp", room = "Living room"),
            live(DemoHome.Ids.SHELF_STRIP, name = "Shelf strip", room = "Living room"),
            live(DemoHome.Ids.BEDSIDE, name = "Bedside", room = "Bedroom"),
            live(DemoHome.Ids.TV, name = "LG OLED", room = "Living room"),
            live(DemoHome.Ids.PRINTER, name = "Workshop H2D", room = "Workshop"),
            live(DemoHome.Ids.CAMERA, name = "Front door", pair = true),
            live(DemoHome.Ids.THERMOSTAT, name = "Hall thermostat"),
            live(DemoHome.Ids.PURIFIER, reach = Reachability.OFFLINE, name = "Air purifier"),
            live(DemoHome.Ids.GOOGLE_TV, reach = Reachability.DEGRADED, name = "Living room cast", trust = true),
        ),
    )
    override val devices: StateFlow<List<LiveDevice>> = deviceFlow
    override val scanState: StateFlow<ScanState> = MutableStateFlow(ScanState.DONE)
    override val netStats: StateFlow<NetGuardStats> = MutableStateFlow(NetGuardStats(allowed = 214, denied = 0))
    override val census: StateFlow<List<Finding>> = MutableStateFlow(emptyList())
    override val problem: StateFlow<String?> = MutableStateFlow(null)
    override val scanReport: StateFlow<List<AdapterScan>> = MutableStateFlow(
        listOf(
            AdapterScan("Govee lights", 0, "Turn on “LAN Control” for each light in the Govee app. If Windows Firewall blocked Patchbay, allow it on Private networks.", true),
            AdapterScan("LG TVs", 1, null, true),
            AdapterScan("Bambu printers", 1, null, true),
            AdapterScan("TP-Link Kasa plugs", 1, "Finds plugs on older firmware only.", true),
            AdapterScan("RTSP cameras", 0, null, false),
        ),
    )
    override fun dismissProblem() {}
    override val activity: StateFlow<List<dev.patchbay.core.home.LedgerEntry>> = MutableStateFlow(
        listOf(
            dev.patchbay.core.home.LedgerEntry(1_000, "Living room cast", "Presented a different security key than the one you paired (stopped talking to it)"),
            dev.patchbay.core.home.LedgerEntry(2_000, "Scene: Movie night", "Ran (2 of 2 devices responded)"),
            dev.patchbay.core.home.LedgerEntry(3_000, "Floor lamp", "Turned on (from Patchbay)"),
            dev.patchbay.core.home.LedgerEntry(4_000, "Air purifier", "Went offline"),
            dev.patchbay.core.home.LedgerEntry(5_000, "Workshop H2D", "Printer: printing (at the device or another app)"),
        ),
    )
    override fun timeLabel(epochMillis: Long): String = "Today 19:${(30 + epochMillis / 1000).toString().padStart(2, '0')}"
    override fun activityCsv(): String = "csv"
    /** What the screens asked of the home, in order; a test reads these and sets the answers below. */
    val sent = CopyOnWriteArrayList<Pair<DeviceId, Command>>()
    val forgotten = CopyOnWriteArrayList<DeviceId>()
    val pairRequests = CopyOnWriteArrayList<DeviceId>()
    val scenesRun = CopyOnWriteArrayList<String>()
    @Volatile var sendResult: () -> PatchbayResult<Unit> = { PatchbayResult.Ok(Unit) }
    @Volatile var runSceneResult: () -> PatchbayResult<Unit> = { PatchbayResult.Ok(Unit) }
    @Volatile var diagnosticsHeaders: List<String>? = null

    override suspend fun forget(device: DeviceId) { forgotten += device }
    override suspend fun allOff(): PatchbayResult<Int> = PatchbayResult.Ok(2)
    override val rooms: StateFlow<List<String>> = MutableStateFlow(listOf("Bedroom", "Living room", "Workshop"))
    override suspend fun assignRoom(device: DeviceId, room: String?) {}
    override val scenes: StateFlow<List<dev.patchbay.core.home.Scene>> = MutableStateFlow(
        listOf(
            dev.patchbay.core.home.Scene("1", "Movie night", listOf(DemoHome.Ids.FLOOR_LAMP, DemoHome.Ids.SHELF_STRIP)),
            dev.patchbay.core.home.Scene("2", "Good night", listOf(DemoHome.Ids.FLOOR_LAMP, DemoHome.Ids.SHELF_STRIP, DemoHome.Ids.BEDSIDE)),
        ),
    )
    override val automations: StateFlow<List<dev.patchbay.core.home.Automation>> = MutableStateFlow(
        listOf(
            dev.patchbay.core.home.Automation("a1", "Evening lights", "1", "Movie night", 19, 30, setOf(1, 2, 3, 4, 5, 6, 7), true),
            dev.patchbay.core.home.Automation("a2", "Bedtime", "2", "Good night", 23, 0, setOf(1, 2, 3, 4, 5), true),
            dev.patchbay.core.home.Automation("a3", "At dusk", "1", "Movie night", 0, 0, setOf(1, 2, 3, 4, 5, 6, 7), true, "sunset", 30),
        ),
    )
    override suspend fun saveAutomation(name: String, sceneId: String, hour: Int, minute: Int, days: Set<Int>, sun: String?, offsetMinutes: Int): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
    override val trends: dev.patchbay.core.model.TrendSource = dev.patchbay.core.model.TrendSource { _, _ -> null }
    override val quietHours: StateFlow<dev.patchbay.core.home.QuietHours?> = MutableStateFlow(dev.patchbay.core.home.QuietHours(22 * 60, 7 * 60))
    override suspend fun setQuietHours(quiet: dev.patchbay.core.home.QuietHours?) {}
    override val location: StateFlow<dev.patchbay.core.home.HomeLocation?> = MutableStateFlow(dev.patchbay.core.home.HomeLocation(51.5, -0.12))
    override suspend fun setLocation(latitude: Double, longitude: Double): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
    override val favourites: StateFlow<Set<DeviceId>> = MutableStateFlow(setOf(DemoHome.Ids.FLOOR_LAMP, DemoHome.Ids.PRINTER))
    override suspend fun setFavourite(device: DeviceId, on: Boolean) {}
    override suspend fun rename(device: DeviceId, name: String): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
    override val notifications: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()
    override suspend fun setAutomationEnabled(id: String, enabled: Boolean) {}
    override suspend fun deleteAutomation(id: String) {}
    override val adaptive: StateFlow<Set<DeviceId>> = MutableStateFlow(emptySet())
    override suspend fun setAdaptive(device: DeviceId, on: Boolean) {}
    override val away: StateFlow<dev.patchbay.core.home.AwayState> = MutableStateFlow(dev.patchbay.core.home.AwayState())
    override suspend fun setAway(active: Boolean, simulate: Set<DeviceId>) {}
    override suspend fun saveScene(name: String, devices: List<DeviceId>): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
    override suspend fun runScene(id: String): PatchbayResult<Unit> { scenesRun += id; return runSceneResult() }
    override suspend fun deleteScene(id: String) {}
    /** What the backup panel was asked to do; the answers are set by a test. */
    val backupRequests = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var backupResult: PatchbayResult<String> = PatchbayResult.Ok("PATCHBAY-BACKUP-1:AAAA")
    @Volatile var restoreResult: PatchbayResult<String> = PatchbayResult.Ok("Restored 3 devices and 2 pairings. Close Patchbay and open it again to use them.")
    override suspend fun createBackup(passphrase: String): PatchbayResult<String> { backupRequests += "create" to passphrase; return backupResult }
    override suspend fun restoreBackup(text: String, passphrase: String): PatchbayResult<String> { backupRequests += "restore:$text" to passphrase; return restoreResult }
    override fun recentLog(maxLines: Int): String = "log"
    override fun diagnosticsReport(header: List<String>): String { diagnosticsHeaders = header; return "diagnostics for " + header.joinToString(" | ") }
    override val manualAdd: List<ManualAdd> = listOf(ManualAdd(AdapterId("rtsp"), "RTSP camera", "Name"))
    override suspend fun addManually(adapter: AdapterId, address: String, key: String, choice: String?): PatchbayResult<DeviceId> =
        PatchbayResult.Err(PatchbayError.Unsupported("screenshot fake"))
    override suspend fun scan(): PatchbayResult<Int> = PatchbayResult.Ok(devices.value.size)
    override suspend fun pair(device: DeviceId, prompt: PairingPrompt): PatchbayResult<Unit> { pairRequests += device; return PatchbayResult.Ok(Unit) }
    override suspend fun streamLease(device: DeviceId): PatchbayResult<StreamLease> = PatchbayResult.Err(PatchbayError.Unsupported("screenshot fake"))
    override suspend fun send(device: DeviceId, command: Command): PatchbayResult<Unit> { sent += device to command; return sendResult() }
}
