package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.bambu.BambuAdapter
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.PrintAction
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test: the real BambuAdapter against a simulated H2D (MQTT over loopback TLS). */
class BambuContractTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private val printer = BambuSimulator("127.0.0.20", "0948AD4A0B00001", "12345678", keystore("device-a.p12"), "testpass".toCharArray())
    private val adapter = BambuAdapter(
        NetGuard(),
        BambuAdapter.Config(
            ssdpTargets = listOf("127.0.0.20"), ssdpPorts = listOf(printer.ssdpPort), mqttPort = printer.mqttPort,
            scanWindowMs = 500, connectTimeoutMs = 2_000, idleMs = 1_000, minEmitGapMs = 0, bindLoopbackOnly = true, notifyPort = 0,
        ),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Prompt(val code: String?, val trust: Boolean = true) : PairingPrompt {
        var askedFingerprint = false
        override suspend fun askForCode(label: String, length: IntRange): String? = code
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean { askedFingerprint = true; return trust }
        override suspend fun instruct(message: String) {}
    }

    @AfterTest
    fun tearDown() {
        scope.cancel(); adapter.close(); printer.close()
    }

    private suspend fun paired(): dev.patchbay.core.model.DeviceId {
        val d = adapter.discover().toList().single()
        val p = adapter.pair(d, Prompt("12345678")).getOrNull()!!
        adapter.restore(p.deviceId, d.address, p)
        return p.deviceId
    }

    @Test
    fun discoversSerialAndModel(): Unit = runBlocking {
        val d = adapter.discover().toList().single()
        assertEquals("0948AD4A0B00001", d.key)
        assertEquals("H2D", d.model)
        assertEquals("Workshop H2D", d.displayName)
        assertEquals("bambu-0948ad4a0b00001", adapter.deviceId(d).value)
    }

    @Test
    fun manualAddPairsWithoutAnyScan(): Unit = runBlocking {
        val d = adapter.manualDevice("127.0.0.20", " 0948ad4a0b00001 ").getOrNull()!!
        assertEquals("0948AD4A0B00001", d.key)
        assertEquals("bambu-0948ad4a0b00001", adapter.deviceId(d).value)
        assertEquals(adapter.manualAdd.adapter, adapter.id)
        val p = adapter.pair(d, Prompt("12345678")).getOrNull()!! // fingerprint + code, exactly like a scanned device
        adapter.restore(p.deviceId, d.address, p)
        val snap = withTimeout(5_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
        assertEquals(47.0, snap.state<CapabilityState.PrintJobState>()!!.progress.value)
    }

    @Test
    fun manualAddRejectsBadSerialsAndNonLanAddresses() {
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.20", "not a serial!").errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.20", "").errorOrNull())
        assertIs<PatchbayError.NetGuardDenied>(adapter.manualDevice("8.8.8.8", "0948AD4A0B00001").errorOrNull())
        assertTrue(adapter.manualDevice("printer.example.com", "0948AD4A0B00001") is PatchbayResult.Err) // no DNS, ever
    }

    @Test
    fun wrongAccessCodeIsUnauthorized(): Unit = runBlocking {
        val d = adapter.discover().toList().single()
        val prompt = Prompt("00000000")
        assertIs<PatchbayError.Unauthorized>(adapter.pair(d, prompt).errorOrNull())
        assertTrue(prompt.askedFingerprint)
    }

    @Test
    fun observesFullThenIncrementalReports(): Unit = runBlocking {
        val id = paired()
        val snaps = CopyOnWriteArrayList<DeviceSnapshot>()
        scope.launch { adapter.observe(id).collect { snaps += it } }
        withTimeout(5_000) { while (snaps.none { it.reachability == Reachability.ONLINE }) delay(20) }
        val first = snaps.first { it.reachability == Reachability.ONLINE }
        assertEquals(2, first.state<CapabilityState.PrinterThermalsState>()!!.nozzles.size)
        assertEquals(47.0, first.state<CapabilityState.PrintJobState>()!!.progress.value)

        printer.tick(52, 125)
        withTimeout(5_000) { while (snaps.last().state<CapabilityState.PrintJobState>()?.layer != 125) delay(20) }
        val last = snaps.last()
        assertEquals(52.0, last.state<CapabilityState.PrintJobState>()!!.progress.value)
        assertEquals(240, last.state<CapabilityState.PrintJobState>()!!.totalLayers) // merged, not lost
        assertEquals(4, last.state<CapabilityState.FilamentSlotsState>()!!.slots.size)
    }

    @Test
    fun pauseResumeStopAndLight(): Unit = runBlocking {
        val id = paired()
        val snaps = CopyOnWriteArrayList<DeviceSnapshot>()
        scope.launch { adapter.observe(id).collect { snaps += it } }
        withTimeout(5_000) { while (snaps.none { it.reachability == Reachability.ONLINE }) delay(20) }

        assertTrue(adapter.command(id, Command.ControlPrint(PrintAction.PAUSE)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (snaps.last().state<CapabilityState.PrintJobState>()?.stage != PrintStage.PAUSED) delay(20) }
        assertTrue(adapter.command(id, Command.ControlPrint(PrintAction.RESUME)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (snaps.last().state<CapabilityState.PrintJobState>()?.stage != PrintStage.PRINTING) delay(20) }

        assertTrue(adapter.command(id, Command.SetAuxLight("Chamber light", false)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (snaps.last().state<CapabilityState.AuxLightState>()?.on != false) delay(20) }
        assertTrue("ledctrl:chamber_light2" in printer.commands) // H2D's second light switched too

        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.SetPower(false)).errorOrNull())
        assertEquals(listOf("pause", "resume"), printer.commands.filter { !it.startsWith("ledctrl") })
    }

    @Test
    fun changedPrinterKeyIsTrustChanged(): Unit = runBlocking {
        val d = adapter.discover().toList().single()
        val p = adapter.pair(d, Prompt("12345678")).getOrNull()!!
        val impostor = BambuSimulator("127.0.0.21", "0948AD4A0B00001", "12345678", keystore("device-b.p12"), "testpass".toCharArray())
        try {
            val a2 = BambuAdapter(NetGuard(), BambuAdapter.Config(mqttPort = impostor.mqttPort, connectTimeoutMs = 2_000, bindLoopbackOnly = true, notifyPort = 0))
            a2.restore(p.deviceId, "127.0.0.21", p)
            val first = withTimeout(10_000) { a2.observe(p.deviceId).first() }
            assertEquals(Reachability.DEGRADED, first.reachability)
            assertEquals(dev.patchbay.core.adapter.HealthState.TRUST_CHANGED, a2.health(p.deviceId).state)
            assertTrue(impostor.commands.isEmpty())
        } finally {
            impostor.close()
        }
    }
}
