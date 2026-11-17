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
import kotlin.test.assertTrue

/**
 * A device gets a new IP address (a DHCP lease changed, the router rebooted): after one rescan Patchbay must be talking to it at the new address,
 * with its pinned key and pairing intact, without restarting the app. Before this was fixed a rescan only updated what the screen showed.
 */
class DeviceMovesTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private object Yes : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = "314-15-926"
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun aCastDeviceThatMovesIsFoundAgainAfterOneRescan(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = CastSimulator("127.0.0.92", keystore("device-a.p12"), "testpass".toCharArray())
        val port = first.port
        // What the TV announces about itself over mDNS; it stays the same when the address changes.
        val castIds = mapOf("_googlecast._tcp" to "9f1c2d3e4b5a69788796a5b4c3d2e1f0")
        var findings = listOf(Finding("127.0.0.92", emptySet(), setOf("_googlecast._tcp"), setOf(port), "Living room TV", "Chromecast", null, null, castIds))
        val guard = NetGuard()
        val adapter = CastAdapter(guard, { findings }, CastAdapter.Config(port = port, connectTimeoutMs = 1_500, heartbeatMs = 300, minEmitGapMs = 0, maxBackoffMs = 300))
        val home = LanHome(guard, listOf(adapter), scope, null, InMemoryHomeStore())
        var second: CastSimulator? = null
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            withTimeout(10_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }

            // The TV gets a new address (same key, same port).
            first.close()
            second = CastSimulator("127.0.0.93", keystore("device-a.p12"), "testpass".toCharArray(), port = port)
            second.volume = 0.77
            findings = listOf(Finding("127.0.0.93", emptySet(), setOf("_googlecast._tcp"), setOf(port), "Living room TV", "Chromecast", null, null, castIds))
            withTimeout(15_000) { while (home.devices.value.single().snapshot?.reachability == Reachability.ONLINE) delay(50) } // noticed it went away
            home.scan()

            second.pushStatus()
            withTimeout(15_000) {
                while (home.devices.value.single().snapshot?.state<CapabilityState.VolumeState>()?.level != 77 ||
                    home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) { second.pushStatus(); delay(100) }
            }
            assertEquals("127.0.0.93", home.devices.value.single().address)
            assertTrue(!home.devices.value.single().trustChanged) // same key: nothing to alarm about
        } finally {
            second?.close(); first.close(); scope.cancel()
        }
    }

    @Test
    fun aThermostatThatMovesIsFoundAgainEvenWithANewPort(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = HomeKitSimulator("127.0.0.66")
        fun finding(sim: HomeKitSimulator) = Finding(sim.address, emptySet(), setOf("_hap._tcp"), setOf(sim.port), "Hallway", "ecobee Premium", null, HapAdvert(sim.port, sim.accessoryId, 9, true))
        var findings = listOf(finding(first))
        val guard = NetGuard()
        val adapter = HomeKitAdapter(guard, dev.patchbay.core.adapter.cast.CastAdapter.CastFinder { findings }, HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000, pollIntervalMs = 100, maxBackoffMs = 300))
        val driver = DesktopStorage.open(File(Files.createTempDirectory("pbmove").toFile(), "m.db"), ByteArray(32) { 4 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)))
        var second: HomeKitSimulator? = null
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            withTimeout(10_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }

            first.close()
            second = HomeKitSimulator("127.0.0.67", cloneOf = first) // same identity, new address and a new random port
            second.currentTemp = 23.5
            findings = listOf(finding(second))
            withTimeout(15_000) { while (home.devices.value.single().snapshot?.reachability == Reachability.ONLINE) delay(50) }
            home.scan()

            withTimeout(20_000) {
                while (home.devices.value.single().snapshot?.state<CapabilityState.ThermostatState>()?.current?.value != 23.5) delay(100)
            }
            assertEquals("127.0.0.67", home.devices.value.single().address)
            assertTrue(!home.devices.value.single().trustChanged)
        } finally {
            second?.close(); first.close(); adapter.close(); driver.close(); scope.cancel()
        }
    }
}
