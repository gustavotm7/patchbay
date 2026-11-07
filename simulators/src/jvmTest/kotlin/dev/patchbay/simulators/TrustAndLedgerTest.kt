package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.store.DesktopStorage
import dev.patchbay.core.store.InMemoryHomeStore
import dev.patchbay.core.store.SqlHomeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The key-change alert, removing a device, the activity history and "all off", end to end through LanHome. */
class TrustAndLedgerTest {

    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private class Prompt : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = null
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun aChangedKeyIsFlaggedLoudlyAndRemovingThenRePairingAcceptsTheNewKey(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val original = CastSimulator("127.0.0.90", keystore("device-a.p12"), "testpass".toCharArray())
        val port = original.port
        val finding = Finding("127.0.0.90", emptySet(), setOf("_googlecast._tcp"), setOf(port), "Living room TV", "Chromecast", null)
        val guard = NetGuard()
        val adapter = CastAdapter(guard, { listOf(finding) }, CastAdapter.Config(port = port, connectTimeoutMs = 1_500, heartbeatMs = 300, minEmitGapMs = 0, maxBackoffMs = 300))
        val store = InMemoryHomeStore()
        val home = LanHome(guard, listOf(adapter), scope, null, store)
        var impostor: CastSimulator? = null
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.devices.value.single().needsPairing)
            assertTrue(home.pair(id, Prompt()) is PatchbayResult.Ok)
            withTimeout(8_000) { while (home.devices.value.single().snapshot?.reachability != dev.patchbay.core.model.Reachability.ONLINE) delay(50) }
            assertFalse(home.devices.value.single().trustChanged)

            // Something else takes over the address with a different key.
            original.close()
            impostor = CastSimulator("127.0.0.90", keystore("device-b.p12"), "testpass".toCharArray(), port = port)
            withTimeout(15_000) { while (!home.devices.value.single().trustChanged) delay(50) }
            assertTrue(home.problem.value?.contains("different security key") == true)
            assertTrue(home.activity.value.any { it.event.contains("different security key") })

            // The user removes it and pairs again with the key they now see.
            home.forget(id)
            assertEquals(0, home.devices.value.size)
            assertNull(store.pairing(id))
            home.scan()
            assertTrue(home.devices.value.single().needsPairing)
            assertTrue(home.pair(id, Prompt()) is PatchbayResult.Ok)
            withTimeout(15_000) { while (home.devices.value.single().snapshot?.reachability != dev.patchbay.core.model.Reachability.ONLINE) delay(50) }
            assertFalse(home.devices.value.single().trustChanged)
        } finally {
            impostor?.close(); original.close(); scope.cancel()
        }
    }

    private val lampKey = "1f80c5323236724e"
    private val stripKey = "aabbccddeeff0011"
    private val lampId = DeviceId("govee-$lampKey")
    private val stripId = DeviceId("govee-$stripKey")

    private class Session(val scope: CoroutineScope, val home: LanHome, val close: () -> Unit)

    private fun open(db: File, lamp: GoveeSimulator, strip: GoveeSimulator): Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.8", "127.0.0.9"), listenPort = 0, bindLoopbackOnly = true,
                scanPort = lamp.scanPort, commandPort = lamp.commandPort, scanWindowMs = 500, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        val p = (adapter.start() as PatchbayResult.Ok).value
        lamp.replyPort = p; strip.replyPort = p
        val driver = DesktopStorage.open(db, ByteArray(32) { 9 })
        return Session(scope, LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)))) { adapter.close(); driver.close(); scope.cancel() }
    }

    private suspend fun settle(s: Session) {
        repeat(80) {
            if (listOf(lampId, stripId).all { id -> s.home.devices.value.firstOrNull { it.device.id == id }?.snapshot?.states?.isNotEmpty() == true }) return
            delay(50)
        }
    }

    @Test
    fun allOffSwitchesEverythingOffAndTheActivityHistorySurvivesARestart(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbledger").toFile(), "l.db")
        val lamp = GoveeSimulator("127.0.0.8", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val strip = GoveeSimulator("127.0.0.9", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        lamp.on = true; strip.on = true
        val s1 = open(db, lamp, strip)
        s1.home.scan(); settle(s1)

        // A change made at the device itself is noticed and described as such.
        lamp.on = false
        withTimeout(5_000) { while (s1.home.activity.value.none { it.event.startsWith("Turned off") }) delay(50) }
        assertTrue(s1.home.activity.value.first { it.event.startsWith("Turned off") }.event.contains("at the device or another app"))
        lamp.on = true
        withTimeout(5_000) { while (s1.home.devices.value.first { it.device.id == lampId }.snapshot?.state<CapabilityState.PowerState>()?.on != true) delay(50) }

        val r = s1.home.allOff()
        assertEquals(2, (r as PatchbayResult.Ok).value)
        delay(200)
        assertEquals(false, lamp.on); assertEquals(false, strip.on)
        assertTrue(s1.home.activity.value.any { it.deviceName == "All lights and plugs" && it.event.contains("2 of 2") })
        withTimeout(5_000) { while (s1.home.activity.value.count { it.event.startsWith("Turned off") && it.event.contains("from Patchbay") } < 1) delay(50) }
        val csv = s1.home.activityCsv()
        assertTrue(csv.startsWith("time_utc,device,event\n") && csv.lines().size > 3, csv)
        delay(2_600) // the history is written in a batch about two seconds after the last entry
        s1.close()

        val s2 = open(db, lamp, strip)
        assertTrue(s2.home.activity.value.any { it.deviceName == "All lights and plugs" })
        s2.close(); lamp.close(); strip.close()
    }

    @Test
    fun forgettingADeviceRemovesItAndAllOffReportsNothingToDoWhenAllAreOff(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbforget").toFile(), "f.db")
        val lamp = GoveeSimulator("127.0.0.8", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val strip = GoveeSimulator("127.0.0.9", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        lamp.on = false; strip.on = false
        val s = open(db, lamp, strip)
        s.home.scan(); settle(s)
        assertEquals(0, (s.home.allOff() as PatchbayResult.Ok).value)
        s.home.forget(stripId)
        assertEquals(listOf(lampId), s.home.devices.value.map { it.device.id })
        s.close()
        val s2 = open(db, lamp, strip)
        assertEquals(listOf(lampId), s2.home.devices.value.map { it.device.id }) // gone for good
        assertIs<PatchbayError.Unreachable>(s2.home.send(stripId, dev.patchbay.core.model.Command.SetPower(true)).errorOrNull())
        s2.close(); lamp.close(); strip.close()
    }
}
