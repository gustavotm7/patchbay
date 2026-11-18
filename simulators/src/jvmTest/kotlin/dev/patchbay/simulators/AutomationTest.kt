package dev.patchbay.simulators

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.db.PatchbayDb
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
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Automations run a saved scene at a set time on chosen days, once, and only when enabled. A fixed clock drives them. */
class AutomationTest {
    private val lampId = DeviceId("govee-1f80c5323236724e")
    private val utc = ZoneId.of("UTC")

    private fun at(day: Int, h: Int, m: Int): Long = ZonedDateTime.of(2026, 11, day, h, m, 20, 0, utc).toInstant().toEpochMilli() // 2 Nov 2026 is a Monday

    private class Session(val home: LanHome, val close: () -> Unit)

    private fun open(db: File, lamp: GoveeSimulator): Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.10"), listenPort = 0, bindLoopbackOnly = true,
                scanPort = lamp.scanPort, commandPort = lamp.commandPort, scanWindowMs = 400, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        lamp.replyPort = (adapter.start() as PatchbayResult.Ok).value
        val driver = DesktopStorage.open(db, ByteArray(32) { 7 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)), zone = utc)
        return Session(home) { adapter.close(); driver.close(); scope.cancel() }
    }

    @Test
    fun runsOnceAtTheRightMinuteOnTheRightDaysWhenEnabled(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbauto").toFile(), "a.db")
        val lamp = GoveeSimulator("127.0.0.10", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        lamp.on = true; lamp.brightness = 62
        val s = open(db, lamp)
        s.home.scan()
        repeat(60) { if (s.home.devices.value.single().snapshot?.states?.isNotEmpty() == true) return@repeat else delay(50) }
        assertTrue(s.home.saveScene("Evening", listOf(lampId)) is PatchbayResult.Ok)
        val scene = s.home.scenes.value.single()

        // Validation.
        assertIs<PatchbayError.InvalidArgument>(s.home.saveAutomation("", scene.id, 7, 30, setOf(1)).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.saveAutomation("Bad time", scene.id, 24, 0, setOf(1)).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.saveAutomation("No days", scene.id, 7, 30, emptySet()).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.saveAutomation("No scene", "nope", 7, 30, setOf(1)).errorOrNull())

        // Weekdays at 07:30.
        assertTrue(s.home.saveAutomation("Morning", scene.id, 7, 30, setOf(1, 2, 3, 4, 5)) is PatchbayResult.Ok)
        val auto = s.home.automations.value.single()
        assertEquals("Evening", auto.sceneName)

        lamp.on = false
        s.home.tickAutomations(at(2, 7, 29)) // a minute early
        delay(300); assertEquals(false, lamp.on)
        s.home.tickAutomations(at(2, 7, 30)) // Monday, on time
        delay(400); assertEquals(true, lamp.on)
        val runs = s.home.activity.value.count { it.deviceName == "Automation: Morning" }
        s.home.tickAutomations(at(2, 7, 30) + 20_000) // same minute again
        assertEquals(runs, s.home.activity.value.count { it.deviceName == "Automation: Morning" })

        lamp.on = false
        s.home.tickAutomations(at(7, 7, 30)) // Saturday: not a weekday
        delay(300); assertEquals(false, lamp.on)

        s.home.setAutomationEnabled(auto.id, false)
        s.home.tickAutomations(at(3, 7, 30)) // Tuesday but disabled
        delay(300); assertEquals(false, lamp.on)
        s.home.setAutomationEnabled(auto.id, true)
        s.home.tickAutomations(at(3, 7, 30))
        delay(400); assertEquals(true, lamp.on)
        s.close(); lamp.close()
    }

    @Test
    fun aTimeThatFellInASleepGapRunsLateOnceButNotWhenTooOldOrAlreadyPast(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbauto").toFile(), "a.db")
        val lamp = GoveeSimulator("127.0.0.10", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        lamp.on = true; lamp.brightness = 62
        val s = open(db, lamp)
        s.home.scan()
        repeat(60) { if (s.home.devices.value.single().snapshot?.states?.isNotEmpty() == true) return@repeat else delay(50) }
        assertTrue(s.home.saveScene("Evening", listOf(lampId)) is PatchbayResult.Ok) // the scene remembers the lamp as on
        val scene = s.home.scenes.value.single()
        lamp.on = false
        fun lines(title: String) = s.home.activity.value.filter { it.deviceName == "Automation: $title" }.map { it.event }

        assertTrue(s.home.saveAutomation("Early", scene.id, 6, 45, setOf(1, 2, 3, 4, 5)) is PatchbayResult.Ok)
        assertTrue(s.home.saveAutomation("Missed", scene.id, 6, 50, setOf(1, 2, 3, 4, 5)) is PatchbayResult.Ok)

        // The computer looks at 06:40, sleeps, and wakes at 06:48 and again at 07:10.
        s.home.tickAutomations(at(2, 6, 40))
        s.home.tickAutomations(at(2, 6, 48)) // 06:45 fell in the gap, 3 minutes ago: it runs, late
        delay(400)
        assertTrue(lamp.on)
        assertEquals(1, lines("Early").size)
        assertTrue(lines("Early").single().contains("3 minutes late"), lines("Early").toString())
        assertEquals(0, lines("Missed").size)

        lamp.on = false
        s.home.tickAutomations(at(2, 6, 48) + 15_000) // the next tick does not run it again
        s.home.tickAutomations(at(2, 7, 10)) // 06:50 is 20 minutes old: too late, skipped
        delay(300)
        assertEquals(false, lamp.on)
        assertEquals(1, lines("Early").size)
        assertEquals(0, lines("Missed").size)

        // An automation created after its time today does not run for that time.
        assertTrue(s.home.saveAutomation("Created late", scene.id, 7, 0, setOf(1, 2, 3, 4, 5)) is PatchbayResult.Ok)
        s.home.tickAutomations(at(2, 7, 12))
        delay(300)
        assertEquals(0, lines("Created late").size)
        s.close(); lamp.close()
    }

    @Test
    fun surviveARestartAndSkipWhenTheirSceneIsDeleted(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbauto2").toFile(), "a.db")
        val lamp = GoveeSimulator("127.0.0.10", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val s1 = open(db, lamp)
        s1.home.scan()
        repeat(60) { if (s1.home.devices.value.single().snapshot?.states?.isNotEmpty() == true) return@repeat else delay(50) }
        s1.home.saveScene("Evening", listOf(lampId))
        val sceneId = s1.home.scenes.value.single().id
        s1.home.saveAutomation("Nightly", sceneId, 22, 0, setOf(1, 2, 3, 4, 5, 6, 7))
        s1.close()

        val s2 = open(db, lamp)
        assertEquals("Nightly", s2.home.automations.value.single().name)
        s2.home.deleteScene(sceneId)
        assertEquals(null, s2.home.automations.value.single().sceneName)
        s2.home.tickAutomations(at(2, 22, 0))
        assertTrue(s2.home.activity.value.any { it.deviceName == "Automation: Nightly" && it.event.startsWith("Skipped") })
        s2.home.deleteAutomation(s2.home.automations.value.single().id)
        assertEquals(0, s2.home.automations.value.size)
        s2.close(); lamp.close()
    }
}
