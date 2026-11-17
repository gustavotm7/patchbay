package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.adapter.homekit.HomeKitAdapter
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.discovery.HapAdvert
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Celsius
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interop with an independent HomeKit accessory: HAP-python (the reference Python implementation) serving a thermostat on loopback.
 * Not part of CI: it needs a running accessory, so it only runs when PATCHBAY_HAP_PYTHON_PORT (and PATCHBAY_HAP_PYTHON_ID, the accessory's
 * device id) are set. See docs/protocols/homekit.md for how it was run. This is the one check in the repository where our HomeKit code talks to
 * code we did not write.
 */
class HapPythonInteropTest {
    private class Prompt(val code: String) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = code
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun pairsAndTalksToHapPython(): Unit = runBlocking {
        val port = System.getenv("PATCHBAY_HAP_PYTHON_PORT")?.toIntOrNull() ?: return@runBlocking
        val id = System.getenv("PATCHBAY_HAP_PYTHON_ID") ?: return@runBlocking
        val finding = Finding("127.0.0.1", emptySet(), setOf("_hap._tcp"), setOf(port), "Hallway", "HAP-python", null, HapAdvert(port, id, 9, true, 0))
        val adapter = HomeKitAdapter(NetGuard(), CastAdapter.CastFinder { listOf(finding) }, HomeKitAdapter.Config(connectTimeoutMs = 3_000, ioTimeoutMs = 5_000, pollIntervalMs = 300, maxBackoffMs = 1_000))
        try {
            val d = adapter.discover().toList().single()
            val paired = adapter.pair(d, Prompt("314-15-926"))
            println("PAIR RESULT: $paired")
            val p = paired.getOrNull()!!
            adapter.restore(p.deviceId, d.address, p)
            val snap = withTimeout(15_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            val t = snap.state<CapabilityState.ThermostatState>()!!
            println("READ: $t")
            assertEquals(21.5, t.current.value, 0.05)
            val r = adapter.command(p.deviceId, Command.SetSetpoints(Celsius(22.0), null))
            println("SET: $r")
            assertTrue(r is PatchbayResult.Ok)
            delay(800)
            val after = withTimeout(10_000) { adapter.observe(p.deviceId).first { it.state<CapabilityState.ThermostatState>()?.heatSetpoint?.value == 22.0 } }
            println("AFTER: ${after.state<CapabilityState.ThermostatState>()}")
            println("DIAG: " + (adapter.diagnostics(p.deviceId) + adapter.adapterDiagnostics()).joinToString(" || "))
        } finally { adapter.close() }
    }
}
