package dev.patchbay.simulators

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.home.DeviceEvent
import dev.patchbay.core.home.LanHome
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "When a device does X, run a scene": a lamp turning on switches a light strip off. The clock is fixed so the 30-second window after an automation
 * is stepped through by hand.
 */
class DeviceAutomationTest {
    private val lampId = DeviceId("govee-1f80c5323236724e")
    private val stripId = DeviceId("govee-aabbccddeeff0011")

    private class Rig(val home: LanHome, val lamp: GoveeSimulator, val strip: GoveeSimulator, val now: AtomicLong, val close: () -> Unit)

    private fun open(db: File, lamp: GoveeSimulator, strip: GoveeSimulator, now: AtomicLong): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.21", "127.0.0.22"), listenPort = 0, bindLoopbackOnly = true,
                scanPort = lamp.scanPort, commandPort = lamp.commandPort,
                scanWindowMs = 500, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        val port = (adapter.start() as PatchbayResult.Ok).value
        lamp.replyPort = port; strip.replyPort = port
        val driver = DesktopStorage.open(db, ByteArray(32) { 9 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)), clock = { now.get() })
        return Rig(home, lamp, strip, now) { adapter.close(); driver.close(); scope.cancel() }
    }

    private suspend fun settle(r: Rig) {
        repeat(80) {
            if (listOf(lampId, stripId).all { id -> r.home.devices.value.firstOrNull { it.device.id == id }?.snapshot?.states?.isNotEmpty() == true }) return
            delay(50)
        }
    }

    private suspend fun until(what: String, ms: Long = 5_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > end) error("timed out waiting for $what")
            delay(25)
        }
    }

    /** Flips the lamp off and on again and gives the adapter time to see both. */
    private suspend fun flick(r: Rig) {
        r.lamp.on = false; delay(400)
        r.lamp.on = true; delay(600)
    }

    private fun sceneRuns(r: Rig) = r.home.activity.value.count { it.deviceName == "Scene: Wind down" }

    private fun lines(r: Rig, who: String) = r.home.activity.value.filter { it.deviceName == who }.map { it.event }

    private fun sims(): Pair<GoveeSimulator, GoveeSimulator> {
        val lamp = GoveeSimulator("127.0.0.21", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val strip = GoveeSimulator("127.0.0.22", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        return lamp to strip
    }

    @Test
    fun anAutomationThatKeepsFiringIsHeldBackAfterSixRunsInTenMinutes(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbdevauto").toFile(), "a.db")
        val (lamp, strip) = sims()
        lamp.on = true
        strip.on = false // the scene remembers the strip as off
        val now = AtomicLong(2_000_000_000_000L)
        val r = open(db, lamp, strip, now)
        r.home.scan(); settle(r)
        assertTrue(r.home.saveScene("Wind down", listOf(stripId)) is PatchbayResult.Ok)
        val scene = r.home.scenes.value.single()
        assertTrue(r.home.saveDeviceAutomation("Lamp wind down", scene.id, lampId, DeviceEvent.TURNED_ON) is PatchbayResult.Ok)
        delay(700)

        // The lamp comes on every 60 seconds, well outside the 30-second chain window: the first six run, the rest are held back.
        repeat(8) { i ->
            strip.on = true; delay(300)
            now.addAndGet(60_000)
            flick(r)
            if (i < 6) {
                until("run ${i + 1}") { !strip.on }
                until("run ${i + 1} to finish") { sceneRuns(r) == i + 1 }
            } else {
                delay(300)
            }
        }
        val l = lines(r, "Automation: Lamp wind down")
        assertEquals(6, l.count { it.startsWith("Running scene") }, l.toString())
        assertTrue(l.any { it.contains("being held back") }, l.toString())
        assertTrue(strip.on, "the seventh and eighth must not have run")

        // Ten minutes after the first run it may run again.
        now.addAndGet(11 * 60_000L)
        flick(r)
        until("the scene to run again") { !strip.on }
        r.close(); lamp.close(); strip.close()
    }

    @Test
    fun aDeviceEventRunsTheSceneOnlyForThatEventAndNeverChains(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbdevauto").toFile(), "a.db")
        val (lamp, strip) = sims()
        lamp.on = true // already on when Patchbay starts: a baseline, not an event
        strip.on = false
        val now = AtomicLong(2_000_000_000_000L)
        val r = open(db, lamp, strip, now)
        r.home.scan(); settle(r)
        assertTrue(r.home.saveScene("Wind down", listOf(stripId)) is PatchbayResult.Ok) // the strip off
        val scene = r.home.scenes.value.single()
        strip.on = true

        // Only what the device can report, a real scene and a sensible name.
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("Bad", scene.id, lampId, DeviceEvent.PRINT_FINISHED).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("Bad", "nope", lampId, DeviceEvent.TURNED_ON).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("Bad", scene.id, DeviceId("nobody"), DeviceEvent.TURNED_ON).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(r.home.saveDeviceAutomation("  ", scene.id, lampId, DeviceEvent.TURNED_ON).errorOrNull())
        assertEquals(0, r.home.automations.value.size)

        assertTrue(r.home.saveDeviceAutomation("Lamp wind down", scene.id, lampId, DeviceEvent.TURNED_ON) is PatchbayResult.Ok)
        val auto = r.home.automations.value.single()
        assertEquals(lampId, auto.trigger?.device)
        assertEquals(DeviceEvent.TURNED_ON, auto.trigger?.event)
        assertNotNull(auto.trigger?.deviceName)
        assertEquals("Wind down", auto.sceneName)

        delay(700)
        assertTrue(strip.on, "the lamp being on at start is not an event")
        lamp.on = false; delay(700)
        assertTrue(strip.on, "turning off is a different event")
        assertEquals(emptyList(), lines(r, "Automation: Lamp wind down"))

        lamp.on = true
        until("the scene to run") { !strip.on }
        until("the first run to finish") { sceneRuns(r) == 1 } // the 30 seconds count from when the scene ends, so let it end before moving the clock
        assertTrue(lines(r, "Automation: Lamp wind down").any { it.startsWith("Running scene Wind down because") && it.endsWith("turns on") })

        // Ten seconds later the same thing happens again: inside the 30-second window, so nothing runs and Activity says why.
        now.addAndGet(10_000)
        strip.on = true; delay(300)
        flick(r)
        assertTrue(strip.on, "an automation must not start within 30 seconds of another")
        assertTrue(lines(r, "Automation: Lamp wind down").any { it.startsWith("Not run:") })

        // After the window it runs again.
        now.addAndGet(25_000)
        flick(r)
        until("the scene to run again") { !strip.on }
        until("the second run to finish") { sceneRuns(r) == 2 }
        assertEquals(2, lines(r, "Automation: Lamp wind down").count { it.startsWith("Running scene") })

        // Switched off, it does nothing.
        r.home.setAutomationEnabled(auto.id, false)
        now.addAndGet(60_000)
        strip.on = true; delay(300)
        flick(r)
        assertTrue(strip.on)
        r.close(); lamp.close(); strip.close()
    }

    @Test
    fun everyAutomationWaitingForTheSameEventRuns(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbdevauto2").toFile(), "a.db")
        val (lamp, strip) = sims()
        lamp.on = false; strip.on = false; lamp.brightness = 62
        val now = AtomicLong(2_000_000_000_000L)
        val r = open(db, lamp, strip, now)
        r.home.scan(); settle(r)
        r.home.saveScene("Strip off", listOf(stripId))
        r.home.saveScene("Lamp soft", listOf(lampId)) // power off here: the scene would switch the lamp off, so give it a state worth restoring
        lamp.on = true; lamp.brightness = 30; delay(400)
        r.home.saveScene("Lamp soft", listOf(lampId)) // replaces the one above: on at 30%
        val stripOff = r.home.scenes.value.first { it.name == "Strip off" }
        val soft = r.home.scenes.value.first { it.name == "Lamp soft" }
        lamp.on = false; strip.on = true; lamp.brightness = 80; delay(500)

        r.home.saveDeviceAutomation("One", stripOff.id, lampId, DeviceEvent.TURNED_ON)
        r.home.saveDeviceAutomation("Two", soft.id, lampId, DeviceEvent.TURNED_ON)
        lamp.on = true
        until("both scenes to run") { !strip.on && lamp.brightness == 30 }
        assertEquals(1, lines(r, "Automation: One").count { it.startsWith("Running scene") })
        assertEquals(1, lines(r, "Automation: Two").count { it.startsWith("Running scene") })
        r.close(); lamp.close(); strip.close()
    }

    @Test
    fun triggersSurviveARestartAndShowWhenTheirDeviceIsForgotten(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbdevauto3").toFile(), "a.db")
        val (lamp, strip) = sims()
        val now = AtomicLong(2_000_000_000_000L)
        val r1 = open(db, lamp, strip, now)
        r1.home.scan(); settle(r1)
        r1.home.saveScene("Wind down", listOf(stripId))
        r1.home.saveDeviceAutomation("Lamp wind down", r1.home.scenes.value.single().id, lampId, DeviceEvent.TURNED_OFF)
        r1.close()

        val r2 = open(db, lamp, strip, now)
        val a = r2.home.automations.value.single()
        assertEquals(DeviceEvent.TURNED_OFF, a.trigger?.event)
        assertEquals(lampId, a.trigger?.device)
        assertNotNull(a.trigger?.deviceName, "the saved lamp's name is found after a restart")
        r2.home.forget(lampId)
        assertNull(r2.home.automations.value.single().trigger?.deviceName, "a forgotten device leaves the automation visibly orphaned")
        r2.close(); lamp.close(); strip.close()
    }
}
