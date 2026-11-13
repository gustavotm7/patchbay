package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.adapter.homekit.HomeKitAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.discovery.HapAdvert
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Metric
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThermostatTrendTest {
    private object Yes : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = "314-15-926"
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun temperatureAndHumidityHistoryAccumulatesAndSurvivesARestart(): Unit = runBlocking {
        val sim = HomeKitSimulator("127.0.0.65")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val finding = Finding(sim.address, emptySet(), setOf("_hap._tcp"), setOf(sim.port), "Hallway", "ecobee Premium", null, HapAdvert(sim.port, sim.accessoryId, 9, true))
        val adapter = HomeKitAdapter(guard, CastAdapter.CastFinder { listOf(finding) }, HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000, pollIntervalMs = 100))
        val dbFile = File(Files.createTempDirectory("pbtrend").toFile(), "t.db")
        var now = 1_800_000_000_000L
        val driver = DesktopStorage.open(dbFile, ByteArray(32) { 3 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)), clock = { now })
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            suspend fun waitTemp(c: Double) = withTimeout(10_000) {
                while (home.devices.value.single().snapshot?.state<CapabilityState.ThermostatState>()?.current?.value != c) delay(50)
            }
            waitTemp(19.5)
            assertEquals(null, home.trends.trend(id, Metric.INDOOR_TEMPERATURE)) // one sample is not a trend

            now += 6 * 60_000; sim.currentTemp = 20.5; waitTemp(20.5)
            now += 6 * 60_000; sim.currentTemp = 21.5; waitTemp(21.5)
            val t = assertNotNull(home.trends.trend(id, Metric.INDOOR_TEMPERATURE))
            assertEquals(19.5, t.values.first()); assertEquals(21.5, t.values.last())
            assertNotNull(home.trends.trend(id, Metric.HUMIDITY))

            // The history is saved with the rest of the encrypted data and is there after a restart.
            driver.close()
            val driver2 = DesktopStorage.open(dbFile, ByteArray(32) { 3 })
            try {
                val again = LanHome(guard, emptyList(), scope, null, SqlHomeStore(PatchbayDb(driver2)), clock = { now })
                val saved = assertNotNull(again.trends.trend(id, Metric.INDOOR_TEMPERATURE))
                assertEquals(19.5, saved.values.first())
            } finally { driver2.close() }
        } finally {
            adapter.close(); sim.close(); scope.cancel()
        }
    }
}
