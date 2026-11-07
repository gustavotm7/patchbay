package dev.patchbay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.patchbay.simulators.DemoHome
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.PatchbayColors
import dev.patchbay.ui.designsystem.PatchbayPalettes
import dev.patchbay.ui.designsystem.PatchbayTheme
import dev.patchbay.ui.screens.DesignSheet
import dev.patchbay.ui.screens.DesktopShell
import dev.patchbay.ui.screens.DeviceScreenPhone
import dev.patchbay.ui.screens.Focus
import dev.patchbay.ui.screens.HomeScreenPhone
import dev.patchbay.ui.screens.LightPanel
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
import dev.patchbay.ui.screens.ActivityScreen
import dev.patchbay.ui.screens.AppSettings
import dev.patchbay.ui.screens.Appearance
import dev.patchbay.ui.screens.NetworkScreen
import dev.patchbay.ui.screens.SettingsScreen
import dev.patchbay.ui.screens.PhoneApp
import dev.patchbay.ui.screens.Section
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the key screens in every theme at phone and desktop sizes, from simulator data.
 * Output: build/screenshots-actual/<name>.png; when goldens exist in docs/design/screens/ they
 * are compared (tolerant of tiny antialiasing differences). `./gradlew :ui:recordScreens` rewrites them.
 */
class ScreenshotTest {

    private val actualDir = File(System.getProperty("patchbay.screens.actual") ?: "build/screenshots-actual")
    private val goldenDir = File(System.getProperty("patchbay.screens.golden") ?: "../docs/design/screens")
    private val record = System.getProperty("patchbay.screens.record") == "true"

    private val home = DemoHome.home()
    private val trends = DemoHome.trends
    private val now = Fmt.timeOfDay(DemoHome.DEMO_NOW.value)

    private data class Size(val w: Int, val h: Int, val density: Float)

    // Phone: 412 × 915 dp (typical 6.x" Android) at 2×. Desktop: 1440 × 900 dp at 1×.
    private val phone = Size(412, 915, 2f)
    private val phoneTall = Size(412, 1480, 2f)
    private val desktop = Size(1440, 900, 1f)
    private val sheet = Size(1440, 1040, 1f)

    private val failures = mutableListOf<String>()

    /** A fixed set of live devices for the tile screens: lights on and off, a plug, a TV, a printer, a camera, an unreachable one, one to pair. */
    private class FakeLiveHome(private val home: dev.patchbay.core.model.Home) : LiveHome {
        private fun live(id: DeviceId, reach: Reachability? = null, pair: Boolean = false, name: String? = null, room: String? = null, trust: Boolean = false, addr: String = "192.168.1.${20 + id.value.length}"): LiveDevice {
            val d = home.device(id)!!.let { if (name != null) it.copy(name = name) else it }.copy(roomId = room?.let { dev.patchbay.core.model.RoomId(it) })
            val snap = home.snapshot(id)?.let { sn -> if (reach != null) sn.copy(reachability = reach, states = emptyList()) else sn }
            return LiveDevice(d, if (pair) null else snap, addr, needsPairing = pair, trustChanged = trust)
        }

        override val devices: StateFlow<List<LiveDevice>> = MutableStateFlow(
            listOf(
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
        override suspend fun forget(device: DeviceId) {}
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
            ),
        )
        override suspend fun saveAutomation(name: String, sceneId: String, hour: Int, minute: Int, days: Set<Int>): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
        override suspend fun setAutomationEnabled(id: String, enabled: Boolean) {}
        override suspend fun deleteAutomation(id: String) {}
        override suspend fun saveScene(name: String, devices: List<DeviceId>): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
        override suspend fun runScene(id: String): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
        override suspend fun deleteScene(id: String) {}
        override fun recentLog(maxLines: Int): String = "log"
        override val manualAdd: List<ManualAdd> = listOf(ManualAdd(AdapterId("rtsp"), "RTSP camera", "Name"))
        override suspend fun addManually(adapter: AdapterId, address: String, key: String, choice: String?): PatchbayResult<DeviceId> =
            PatchbayResult.Err(PatchbayError.Unsupported("screenshot fake"))
        override suspend fun scan(): PatchbayResult<Int> = PatchbayResult.Ok(devices.value.size)
        override suspend fun pair(device: DeviceId, prompt: PairingPrompt): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
        override suspend fun streamLease(device: DeviceId): PatchbayResult<StreamLease> = PatchbayResult.Err(PatchbayError.Unsupported("screenshot fake"))
        override suspend fun send(device: DeviceId, command: Command): PatchbayResult<Unit> = PatchbayResult.Ok(Unit)
    }

    @Test
    fun renderAll() {
        for (palette in PatchbayPalettes.all) {
            val t = palette.name
            shot("home_phone_$t", phoneTall, palette, FormFactor.PHONE) { HomeScreenPhone(home, now) }
            shot("thermostat_phone_$t", phoneTall, palette, FormFactor.PHONE) { DeviceScreenPhone(home, trends, DemoHome.Ids.THERMOSTAT) }
            shot("printer_phone_$t", phoneTall, palette, FormFactor.PHONE) { DeviceScreenPhone(home, trends, DemoHome.Ids.PRINTER) }
            shot("home_desktop_$t", desktop, palette, FormFactor.DESKTOP) {
                DesktopShell(home, trends, Focus.RoomFocus(DemoHome.Ids.WORKSHOP), now)
            }
            shot("thermostat_desktop_$t", desktop, palette, FormFactor.DESKTOP) {
                DesktopShell(home, trends, Focus.DeviceFocus(DemoHome.Ids.THERMOSTAT), now)
            }
            shot("printer_desktop_$t", desktop, palette, FormFactor.DESKTOP) {
                DesktopShell(home, trends, Focus.DeviceFocus(DemoHome.Ids.PRINTER), now)
            }
            shot("designsystem_$t", sheet, palette, FormFactor.DESKTOP) { DesignSheet() }
            shot("live_phone_$t", phone, palette, FormFactor.PHONE) { PhoneApp(home, trends, now, FakeLiveHome(home)) }
            shot("light_phone_$t", phone, palette, FormFactor.PHONE) {
                val lamp = home.device(DemoHome.Ids.FLOOR_LAMP)!!
                Column(Modifier.fillMaxSize().background(palette.background).padding(16.dp)) {
                    LightPanel(LiveDevice(lamp, home.snapshot(lamp.id)), onCommand = {})
                }
            }
        }
        shot("network_phone_dark", phoneTall, PatchbayPalettes.Dark, FormFactor.PHONE) {
            Column(Modifier.fillMaxSize().background(PatchbayPalettes.Dark.background).padding(16.dp)) { NetworkScreen(FakeLiveHome(home)) }
        }
        val settings = AppSettings(
            Appearance.SYSTEM, {}, {}, "windows-dpapi", "C:\\Users\\you\\Desktop\\Patchbay\\PatchbayData", portable = true, version = "0.1.0",
        )
        shot("settings_phone_dark", phoneTall, PatchbayPalettes.Dark, FormFactor.PHONE) {
            Column(Modifier.fillMaxSize().background(PatchbayPalettes.Dark.background).padding(16.dp)) { SettingsScreen(settings) }
        }
        shot("settings_desktop_dark", desktop, PatchbayPalettes.Dark, FormFactor.DESKTOP) {
            DesktopShell(home, trends, Focus.RoomFocus(DemoHome.Ids.WORKSHOP), now, section = Section.SETTINGS, live = FakeLiveHome(home), settings = settings)
        }
        shot("activity_phone_dark", phoneTall, PatchbayPalettes.Dark, FormFactor.PHONE) {
            Column(Modifier.fillMaxSize().background(PatchbayPalettes.Dark.background).padding(16.dp)) { ActivityScreen(FakeLiveHome(home)) }
        }
        shot("live_desktop_dark", desktop, PatchbayPalettes.Dark, FormFactor.DESKTOP) {
            DesktopShell(home, trends, Focus.RoomFocus(DemoHome.Ids.WORKSHOP), now, section = Section.INVENTORY, live = FakeLiveHome(home))
        }
        // Above-the-fold phone render (what you see without scrolling).
        shot("home_phone_dark_fold", phone, PatchbayPalettes.Dark, FormFactor.PHONE) { HomeScreenPhone(home, now) }
        if (failures.isNotEmpty()) fail("Screenshots differ from goldens:\n" + failures.joinToString("\n"))
    }

    private fun shot(name: String, size: Size, palette: PatchbayColors, ff: FormFactor, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = (size.w * size.density).toInt(),
            height = (size.h * size.density).toInt(),
            density = Density(size.density),
        ) {
            PatchbayTheme(palette, DesktopFonts.typefaces, ff) { content() }
        }
        val png = try {
            scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed for $name")
        } finally {
            scene.close()
        }

        actualDir.mkdirs()
        File(actualDir, "$name.png").writeBytes(png)
        val golden = File(goldenDir, "$name.png")
        if (record) {
            goldenDir.mkdirs()
            golden.writeBytes(png)
        } else if (golden.exists()) {
            val diff = differingFraction(golden.readBytes(), png)
            if (diff > 0.002) failures += "$name: ${"%.3f".format(diff * 100)} % of pixels differ"
        }
    }

    /** Fraction of pixels whose channels differ by more than 24/255 (ignores antialiasing noise). */
    private fun differingFraction(a: ByteArray, b: ByteArray): Double {
        val ia = Image.makeFromEncoded(a)
        val ib = Image.makeFromEncoded(b)
        if (ia.width != ib.width || ia.height != ib.height) return 1.0
        val pa = org.jetbrains.skia.Bitmap.makeFromImage(ia)
        val pb = org.jetbrains.skia.Bitmap.makeFromImage(ib)
        var bad = 0L
        for (y in 0 until ia.height) for (x in 0 until ia.width) {
            val ca = pa.getColor(x, y)
            val cb = pb.getColor(x, y)
            if (ca != cb) {
                val d = maxOf(
                    abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)),
                    abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)),
                    abs((ca and 0xFF) - (cb and 0xFF)),
                )
                if (d > 24) bad++
            }
        }
        return bad.toDouble() / (ia.width.toLong() * ia.height)
    }
}
