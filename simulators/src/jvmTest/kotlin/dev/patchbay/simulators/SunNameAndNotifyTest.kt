package dev.patchbay.simulators

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.home.SunTimes
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SunNameAndNotifyTest {
    private val london = ZoneId.of("Europe/London")
    private val lampId = DeviceId("govee-1f80c5323236724e")

    private class Session(val home: LanHome, val close: () -> Unit)

    private fun open(db: File, lamp: GoveeSimulator, zone: ZoneId, clock: () -> Long = { System.currentTimeMillis() }): Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.12"), listenPort = 0, bindLoopbackOnly = true,
                scanPort = lamp.scanPort, commandPort = lamp.commandPort, scanWindowMs = 400, statusTimeoutMs = 300, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        lamp.replyPort = (adapter.start() as PatchbayResult.Ok).value
        val driver = DesktopStorage.open(db, ByteArray(32) { 11 })
        return Session(LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)), clock = clock, zone = zone)) { adapter.close(); driver.close(); scope.cancel() }
    }

    private suspend fun ready(s: Session) {
        s.home.scan()
        repeat(60) { if (s.home.devices.value.singleOrNull()?.snapshot?.states?.isNotEmpty() == true) return else delay(50) }
    }

    @Test
    fun aSunsetAutomationRunsAtTheComputedLocalMinutePlusOffset(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbsun").toFile(), "s.db")
        val lamp = GoveeSimulator("127.0.0.12", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        lamp.on = true
        val s = open(db, lamp, london); ready(s)
        s.home.saveScene("Evening", listOf(lampId))
        val sceneId = s.home.scenes.value.single().id

        // Needs a location first; bad coordinates are refused.
        assertIs<PatchbayError.InvalidArgument>(s.home.saveAutomation("At dusk", sceneId, 0, 0, setOf(1, 2, 3, 4, 5, 6, 7), "sunset", 0).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.setLocation(95.0, 0.0).errorOrNull())
        assertTrue(s.home.setLocation(51.5074, -0.1278) is PatchbayResult.Ok)
        assertIs<PatchbayError.InvalidArgument>(s.home.saveAutomation("At dusk", sceneId, 0, 0, setOf(1), "noon", 0).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.saveAutomation("At dusk", sceneId, 0, 0, setOf(1), "sunset", 500).errorOrNull())
        assertTrue(s.home.saveAutomation("At dusk", sceneId, 0, 0, setOf(1, 2, 3, 4, 5, 6, 7), "sunset", 30) is PatchbayResult.Ok)
        assertEquals("sunset", s.home.automations.value.single().sun)

        // 2 November 2026 (day 306) in London (GMT): work out the expected local minute independently.
        val (_, setUtc) = SunTimes.compute(51.5074, -0.1278, 306)!!
        val expected = ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, ZoneOffset.UTC).plusSeconds((setUtc * 60).toLong() + 30 * 60).withZoneSameInstant(london)
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 2, h, m, 20, 0, london).toInstant().toEpochMilli()
        lamp.on = false
        s.home.tickAutomations(at(expected.hour, expected.minute - 1)); delay(300); assertEquals(false, lamp.on) // a minute early
        s.home.tickAutomations(at(expected.hour, expected.minute)); delay(500); assertEquals(true, lamp.on) // on time
        s.close(); lamp.close()
    }

    @Test
    fun renameAndFavouritesAndLocationSurviveARestart(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbname").toFile(), "n.db")
        val lamp = GoveeSimulator("127.0.0.12", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val s1 = open(db, lamp, london); ready(s1)
        assertIs<PatchbayError.InvalidArgument>(s1.home.rename(lampId, "").errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s1.home.rename(lampId, "bad/name").errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s1.home.rename(DeviceId("nope"), "Lamp").errorOrNull())
        assertTrue(s1.home.rename(lampId, "  Reading   lamp ") is PatchbayResult.Ok)
        assertEquals("Reading lamp", s1.home.devices.value.single().device.name)
        s1.home.setFavourite(lampId, true)
        s1.home.setLocation(51.5, -0.12)
        s1.close()

        val s2 = open(db, lamp, london)
        assertEquals("Reading lamp", s2.home.devices.value.single().device.name)
        assertEquals(setOf(lampId), s2.home.favourites.value)
        assertEquals(51.5, s2.home.location.value?.latitude)
        s2.home.scan() // a rescan keeps the name you chose
        assertEquals("Reading lamp", s2.home.devices.value.single().device.name)
        s2.home.setFavourite(lampId, false)
        assertEquals(emptySet(), s2.home.favourites.value)
        s2.close(); lamp.close()
    }

    @Test
    fun goingOfflineNotifiesOncePerHour(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbnotify").toFile(), "o.db")
        val lamp = GoveeSimulator("127.0.0.12", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        var now = Instant.parse("2026-11-02T12:00:00Z").toEpochMilli()
        val s = open(db, lamp, london) { now }; ready(s)
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        val job = CoroutineScope(Dispatchers.IO).launch { s.home.notifications.collect { got += it } }
        delay(200)
        lamp.offline = true
        withTimeout(10_000) { while (got.isEmpty()) delay(50) }
        assertTrue(got.single().endsWith("stopped answering."))
        lamp.offline = false
        delay(1_500) // comes back
        lamp.offline = true
        delay(2_500)
        assertEquals(1, got.size) // same hour: no second notification
        job.cancel(); s.close(); lamp.close()
    }
}
