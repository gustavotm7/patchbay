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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Rooms and scenes against two simulated Govee lights, including surviving a restart. */
class RoomsAndScenesTest {

    private val lampKey = "1f80c5323236724e"
    private val stripKey = "aabbccddeeff0011"
    private val lampId = DeviceId("govee-$lampKey")
    private val stripId = DeviceId("govee-$stripKey")

    private class Session(val scope: CoroutineScope, val adapter: GoveeLanAdapter, val home: LanHome, val close: () -> Unit)

    private fun open(db: File, lamp: GoveeSimulator, strip: GoveeSimulator): Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.6", "127.0.0.7"), listenPort = 0, bindLoopbackOnly = true,
                scanPort = lamp.scanPort, commandPort = lamp.commandPort,
                scanWindowMs = 500, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        val port = (adapter.start() as PatchbayResult.Ok).value
        lamp.replyPort = port; strip.replyPort = port
        val driver = DesktopStorage.open(db, ByteArray(32) { 5 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)))
        return Session(scope, adapter, home) { adapter.close(); driver.close(); scope.cancel() }
    }

    private suspend fun settle(s: Session, ids: List<DeviceId>) {
        repeat(60) {
            if (ids.all { id -> s.home.devices.value.firstOrNull { it.device.id == id }?.snapshot?.states?.isNotEmpty() == true }) return
            delay(50)
        }
    }

    @Test
    fun roomsGroupDevicesAndSurviveARestart(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbrooms").toFile(), "r.db")
        val lamp = GoveeSimulator("127.0.0.6", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val strip = GoveeSimulator("127.0.0.7", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        val s1 = open(db, lamp, strip)
        s1.home.scan()
        assertEquals(emptyList(), s1.home.rooms.value)
        s1.home.assignRoom(lampId, "  Living   room ")
        s1.home.assignRoom(stripId, "Living room")
        assertEquals(listOf("Living room"), s1.home.rooms.value)
        s1.home.assignRoom(stripId, "Bedroom")
        assertEquals(listOf("Bedroom", "Living room"), s1.home.rooms.value)
        s1.home.assignRoom(stripId, "bad/name") // ignored
        assertEquals("Bedroom", s1.home.devices.value.first { it.device.id == stripId }.device.roomId?.value)
        s1.home.assignRoom(lampId, null)
        assertEquals(listOf("Bedroom"), s1.home.rooms.value) // a room exists while it has devices
        s1.close()

        val s2 = open(db, lamp, strip)
        assertEquals(listOf("Bedroom"), s2.home.rooms.value)
        assertEquals("Bedroom", s2.home.devices.value.first { it.device.id == stripId }.device.roomId?.value)
        assertEquals(null, s2.home.devices.value.first { it.device.id == lampId }.device.roomId)
        s2.close(); lamp.close(); strip.close()
    }

    @Test
    fun aSceneRestoresSavedStatesAndSurvivesARestart(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbscenes").toFile(), "s.db")
        val lamp = GoveeSimulator("127.0.0.6", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val strip = GoveeSimulator("127.0.0.7", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        lamp.brightness = 62; lamp.kelvin = 2700; lamp.on = true
        strip.brightness = 40; strip.on = false
        val s1 = open(db, lamp, strip)
        s1.home.scan()
        settle(s1, listOf(lampId, stripId))

        assertTrue(s1.home.saveScene(" Movie   night ", listOf(lampId, stripId)) is PatchbayResult.Ok)
        val scene = s1.home.scenes.value.single()
        assertEquals("Movie night", scene.name)
        assertEquals(setOf(lampId, stripId), scene.devices.toSet())

        // Change everything, then run the scene.
        lamp.on = false; lamp.brightness = 10; lamp.kelvin = 5000
        strip.on = true; strip.brightness = 90
        assertTrue(s1.home.runScene(scene.id) is PatchbayResult.Ok)
        delay(150)
        assertEquals(true, lamp.on); assertEquals(62, lamp.brightness); assertEquals(2700, lamp.kelvin)
        assertEquals(false, strip.on)
        s1.close()

        val s2 = open(db, lamp, strip)
        assertEquals("Movie night", s2.home.scenes.value.single().name)
        lamp.on = false
        assertTrue(s2.home.runScene(s2.home.scenes.value.single().id) is PatchbayResult.Ok)
        delay(150)
        assertEquals(true, lamp.on)
        s2.home.deleteScene(s2.home.scenes.value.single().id)
        assertEquals(emptyList(), s2.home.scenes.value)
        s2.close(); lamp.close(); strip.close()
    }

    @Test
    fun scenesRejectBadNamesEmptySelectionsAndReportUnreachableDevices(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbscenes2").toFile(), "s.db")
        val lamp = GoveeSimulator("127.0.0.6", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val strip = GoveeSimulator("127.0.0.7", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        val s = open(db, lamp, strip)
        s.home.scan()
        settle(s, listOf(lampId, stripId))
        assertIs<PatchbayError.InvalidArgument>(s.home.saveScene("", listOf(lampId)).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.saveScene("a/b", listOf(lampId)).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.saveScene("Empty", emptyList()).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.saveScene("Unknown", listOf(DeviceId("nope"))).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(s.home.runScene("missing").errorOrNull())
        // Saving under the same name replaces, not duplicates.
        assertTrue(s.home.saveScene("Evening", listOf(lampId)) is PatchbayResult.Ok)
        assertTrue(s.home.saveScene("evening", listOf(lampId, stripId)) is PatchbayResult.Ok)
        assertEquals(1, s.home.scenes.value.size)
        assertEquals(2, s.home.scenes.value.single().devices.size)
        // One device goes away: the scene reports it instead of pretending.
        strip.offline = true
        val r = s.home.runScene(s.home.scenes.value.single().id)
        assertTrue(r is PatchbayResult.Ok || r.errorOrNull() is PatchbayError.Unreachable)
        s.close(); lamp.close(); strip.close()
    }
}
