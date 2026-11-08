package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.adapter.homekit.HomeKitAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.discovery.HapAdvert
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.store.DesktopStorage
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThermostatSceneTest {
    private object Yes : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = "314-15-926"
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun aSceneRestoresModeAndSetpoints(): Unit = runBlocking {
        val sim = HomeKitSimulator("127.0.0.64")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val finding = Finding(sim.address, emptySet(), setOf("_hap._tcp"), setOf(sim.port), "Hallway", "ecobee Premium", null, HapAdvert(sim.port, sim.accessoryId, 9, true))
        val adapter = HomeKitAdapter(guard, CastAdapter.CastFinder { listOf(finding) }, HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000, pollIntervalMs = 100))
        val driver = DesktopStorage.open(File(Files.createTempDirectory("pbtherm").toFile(), "t.db"), ByteArray(32) { 9 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)))
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            suspend fun waitFor(what: String, cond: () -> Boolean) = withTimeout(10_000) { while (!cond()) delay(50) }
            waitFor("online") { home.devices.value.single().snapshot?.reachability == Reachability.ONLINE && home.devices.value.single().snapshot?.state<CapabilityState.ThermostatState>() != null }

            // "Away": auto, 17 to 27. Save it, then wander off to cooling at 23 and run it again.
            sim.mode = 3; sim.heatThreshold = 17.0; sim.coolThreshold = 27.0
            waitFor("auto") { home.devices.value.single().snapshot?.state<CapabilityState.ThermostatState>()?.coolSetpoint?.value == 27.0 }
            assertTrue(home.saveScene("Away", listOf(id)) is PatchbayResult.Ok)
            sim.mode = 2; sim.targetTemp = 23.0
            waitFor("cooling") { home.devices.value.single().snapshot?.state<CapabilityState.ThermostatState>()?.coolSetpoint?.value == 23.0 }

            assertTrue(home.runScene(home.scenes.value.single().id) is PatchbayResult.Ok)
            waitFor("restored") { sim.mode == 3 && sim.heatThreshold == 17.0 && sim.coolThreshold == 27.0 }
            assertEquals(3, sim.mode)
        } finally {
            adapter.close(); sim.close(); driver.close(); scope.cancel()
        }
    }
}
