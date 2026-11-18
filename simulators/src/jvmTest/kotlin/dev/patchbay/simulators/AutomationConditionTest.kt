package dev.patchbay.simulators

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.home.AutomationCondition
import dev.patchbay.core.home.DayPoint
import dev.patchbay.core.home.DeviceEvent
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.home.TimeWindow
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.store.DesktopStorage
import dev.patchbay.core.store.SqlHomeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conditions on automations ("only after dark", "only when at home"): checked when the automation would run, for device events and for the clock, with the reason in Activity. */
class AutomationConditionTest {
    private val lampId = DeviceId("govee-1f80c5323236724e")
    private val stripId = DeviceId("govee-aabbccddeeff0011")
    private val utc = ZoneId.of("UTC")
    private fun at(day: Int, h: Int, m: Int): Long = ZonedDateTime.of(2026, 11, day, h, m, 20, 0, utc).toInstant().toEpochMilli() // 2 Nov 2026 is a Monday

    private class Rig(val home: LanHome, val lamp: GoveeSimulator, val strip: GoveeSimulator, val now: AtomicLong, val close: () -> Unit)

    private fun open(db: File, lamp: GoveeSimulator, strip: GoveeSimulator, now: AtomicLong): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.97", "127.0.0.98"), listenPort = 0, bindLoopbackOnly = true, scanPort = lamp.scanPort, commandPort = lamp.commandPort,
                scanWindowMs = 500, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        val port = (adapter.start() as PatchbayResult.Ok).value
        lamp.replyPort = port; strip.replyPort = port
        val driver = DesktopStorage.open(db, ByteArray(32) { 11 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)), clock = { now.get() }, zone = utc)
        return Rig(home, lamp, strip, now) { adapter.close(); driver.close(); scope.cancel() }
    }

    private fun sims(): Pair<GoveeSimulator, GoveeSimulator> {
        val lamp = GoveeSimulator("127.0.0.97", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val strip = GoveeSimulator("127.0.0.98", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        return lamp to strip
    }

    private suspend fun ready(r: Rig) {
        r.home.scan()
        repeat(80) { if (listOf(lampId, stripId).all { id -> r.home.devices.value.firstOrNull { it.device.id == id }?.snapshot?.states?.isNotEmpty() == true }) return else delay(50) }
    }

    private suspend fun until(what: String, ms: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check()) { if (System.currentTimeMillis() > end) error("timed out waiting for $what"); delay(25) }
    }

    private suspend fun flick(r: Rig) { r.lamp.on = false; delay(400); r.lamp.on = true; delay(600) }
    private fun lines(r: Rig, who: String) = r.home.activity.value.filter { it.deviceName == who }.map { it.event }

    @Test
    fun aDeviceAutomationOnlyRunsInsideItsWindowAndActivitySaysWhyNot(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbcond").toFile(), "a.db")
        val (lamp, strip) = sims()
        lamp.on = true; strip.on = false
        val now = AtomicLong(at(2, 12, 0)) // noon in London in November: daylight
        val r = open(db, lamp, strip, now)
        ready(r)
        r.home.saveScene("Wind down", listOf(stripId))
        val scene = r.home.scenes.value.single()
        strip.on = true
        r.home.setLocation(51.5, -0.12)
        assertTrue(r.home.saveDeviceAutomation("Dark only", scene.id, lampId, DeviceEvent.TURNED_ON, AutomationCondition(TimeWindow.AFTER_DARK)) is PatchbayResult.Ok)
        assertEquals("only after dark", r.home.automations.value.single().condition?.describe())

        delay(500)
        flick(r)
        assertTrue(strip.on, "in daylight nothing runs")
        assertTrue(lines(r, "Automation: Dark only").any { it.startsWith("Not run:") && it.endsWith("it only runs after dark") }, lines(r, "Automation: Dark only").toString())

        now.set(at(2, 22, 0)) // after dark
        flick(r)
        until("the scene to run after dark") { !strip.on }
        assertTrue(lines(r, "Automation: Dark only").any { it.startsWith("Running scene Wind down") })
        r.close(); lamp.close(); strip.close()
    }

    @Test
    fun theAwayFlagStopsADeviceAutomationAndAClockOne(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbcond2").toFile(), "a.db")
        val (lamp, strip) = sims()
        lamp.on = true; strip.on = false
        val now = AtomicLong(at(2, 12, 0))
        val r = open(db, lamp, strip, now)
        ready(r)
        r.home.saveScene("Wind down", listOf(stripId))
        val scene = r.home.scenes.value.single()
        r.home.saveDeviceAutomation("Home only", scene.id, lampId, DeviceEvent.TURNED_ON, AutomationCondition(whileAway = false))
        r.home.saveAutomation("Morning home", scene.id, 7, 30, (1..7).toSet(), condition = AutomationCondition(whileAway = false))

        r.home.setAway(true, emptySet()) // switches everything off now
        delay(800)
        strip.on = true; lamp.on = false; delay(500)
        lamp.on = true; delay(700) // turns on while away
        assertTrue(strip.on)
        assertTrue(lines(r, "Automation: Home only").any { it.endsWith("it only runs when you are at home") }, lines(r, "Automation: Home only").toString())

        r.home.tickAutomations(at(3, 7, 30))
        delay(500)
        assertTrue(strip.on)
        assertTrue(lines(r, "Automation: Morning home").any { it == "Not run: it only runs when you are at home" }, lines(r, "Automation: Morning home").toString())

        r.home.setAway(false, emptySet())
        now.addAndGet(60_000)
        r.home.tickAutomations(at(4, 7, 30)) // the next morning, at home
        until("the morning scene") { !strip.on }
        assertTrue(lines(r, "Automation: Morning home").any { it.startsWith("Running scene Wind down") })
        r.close(); lamp.close(); strip.close()
    }

    @Test
    fun badConditionsAreRefusedAndAnEmptyOneIsNoCondition(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbcond3").toFile(), "a.db")
        val (lamp, strip) = sims()
        val r = open(db, lamp, strip, AtomicLong(at(2, 12, 0)))
        ready(r)
        r.home.saveScene("Wind down", listOf(stripId))
        val scene = r.home.scenes.value.single()
        val dark = AutomationCondition(TimeWindow.AFTER_DARK)

        // A sun window needs the home location.
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("No place", scene.id, lampId, DeviceEvent.TURNED_ON, dark).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(r.home.saveAutomation("No place", scene.id, 7, 30, setOf(1), condition = dark).errorOrNull())
        r.home.setLocation(51.5, -0.12)
        // A window cannot start and end at the same time, and the times must be real.
        val same = AutomationCondition(TimeWindow(DayPoint.Clock(9, 0), DayPoint.Clock(9, 0)))
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("Same", scene.id, lampId, DeviceEvent.TURNED_ON, same).errorOrNull())
        val broken = AutomationCondition(TimeWindow(DayPoint.Clock(25, 0), DayPoint.Clock(9, 0)))
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("Broken", scene.id, lampId, DeviceEvent.TURNED_ON, broken).errorOrNull())
        val farSun = AutomationCondition(TimeWindow(DayPoint.Sun(true, 400), DayPoint.Clock(9, 0)))
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("Far", scene.id, lampId, DeviceEvent.TURNED_ON, farSun).errorOrNull())
        assertEquals(0, r.home.automations.value.size)

        assertTrue(r.home.saveDeviceAutomation("Plain", scene.id, lampId, DeviceEvent.TURNED_ON, AutomationCondition()) is PatchbayResult.Ok)
        assertNull(r.home.automations.value.single().condition)
        r.close(); lamp.close(); strip.close()
    }

    @Test
    fun aConditionSurvivesARestart(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbcond4").toFile(), "a.db")
        val (lamp, strip) = sims()
        val now = AtomicLong(at(2, 12, 0))
        val r1 = open(db, lamp, strip, now)
        ready(r1)
        r1.home.setLocation(51.5, -0.12)
        r1.home.saveScene("Wind down", listOf(stripId))
        val window = TimeWindow(DayPoint.parse("sunset+15")!!, DayPoint.parse("23:30")!!)
        r1.home.saveDeviceAutomation("Evening", r1.home.scenes.value.single().id, lampId, DeviceEvent.TURNED_ON, AutomationCondition(window, whileAway = false))
        r1.close()

        val r2 = open(db, lamp, strip, now)
        val c = assertNotNull(r2.home.automations.value.single().condition)
        assertEquals(window, c.window)
        assertEquals(false, c.whileAway)
        assertEquals("only from sunset+15 until 23:30, when at home", c.describe())
        r2.close(); lamp.close(); strip.close()
    }
}
