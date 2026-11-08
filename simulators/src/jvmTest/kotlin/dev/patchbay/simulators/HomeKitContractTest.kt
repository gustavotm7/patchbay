package dev.patchbay.simulators

import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.adapter.homekit.HomeKitAdapter
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.discovery.HapAdvert
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Celsius
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.HvacAction
import dev.patchbay.core.model.HvacMode
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real HomeKitAdapter against a simulated thermostat whose crypto comes from the JDK, not from the adapter's own code. */
class HomeKitContractTest {
    private val thermostat = HomeKitSimulator("127.0.0.60")
    private val other = HomeKitSimulator("127.0.0.61", accessoryId = "DD:EE:FF:44:55:66", port = thermostat.port) // same port, other address

    private fun finding(sim: HomeKitSimulator, unpaired: Boolean? = true) =
        Finding(sim.address, emptySet(), setOf("_hap._tcp"), setOf(sim.port), "Hallway", "ecobee Premium", null, HapAdvert(sim.port, sim.accessoryId, 9, unpaired))

    private var findings: List<Finding> = emptyList()
    private val adapter = HomeKitAdapter(
        NetGuard(), CastAdapter.CastFinder { findings },
        HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000, pollIntervalMs = 100, maxBackoffMs = 300),
    )

    private class Prompt(val code: String?, val trust: Boolean = true) : PairingPrompt {
        var fingerprint: String? = null
        override suspend fun askForCode(label: String, length: IntRange): String? = code
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean { fingerprint = sha256; return trust }
        override suspend fun instruct(message: String) {}
    }

    @AfterTest
    fun tearDown() { adapter.close(); thermostat.close(); other.close() }

    private suspend fun paired(sim: HomeKitSimulator = thermostat): DeviceId {
        findings = listOf(finding(sim))
        val d = adapter.discover().toList().single()
        val p = adapter.pair(d, Prompt("314-15-926")).getOrNull()!!
        adapter.restore(p.deviceId, d.address, p)
        return p.deviceId
    }

    private suspend fun snapshot(id: DeviceId, until: (DeviceSnapshot) -> Boolean = { it.reachability == Reachability.ONLINE }) =
        withTimeout(10_000) { adapter.observe(id).first(until) }

    @Test
    fun discoversOnlyThermostatsAndSaysWhenPairedElsewhere(): Unit = runBlocking {
        findings = listOf(
            finding(thermostat),
            Finding("127.0.0.62", emptySet(), setOf("_hap._tcp"), setOf(1234), "Bulb", "Hue", null, HapAdvert(1234, "11:22", 5, true)), // a light bulb: not ours
            Finding("127.0.0.63", emptySet(), setOf("_googlecast._tcp"), setOf(8009), "TV", null, null),
            finding(other, unpaired = false),
        )
        val found = adapter.discover().toList()
        assertEquals(2, found.size)
        assertEquals(DeviceKind.THERMOSTAT, found[0].kind)
        assertEquals("ecobee", found[0].vendor)
        assertEquals("homekit-aabbcc112233", adapter.deviceId(found[0]).value)
        assertTrue("paired to another controller" in found[1].model!!)
    }

    @Test
    fun pairsWithTheSetupCodeAndReadsTheThermostat(): Unit = runBlocking {
        val id = paired()
        assertEquals(1, thermostat.pairedControllers.size)
        val snap = snapshot(id)
        val t = snap.state<CapabilityState.ThermostatState>()!!
        assertEquals(HvacMode.HEAT, t.mode)
        assertEquals(Celsius(19.5), t.current)
        assertEquals(Celsius(21.0), t.heatSetpoint)
        assertEquals(null, t.coolSetpoint)
        assertEquals(HvacAction.HEATING, t.action)
        assertEquals(41.0, snap.state<CapabilityState.HumidityReading>()!!.relative.value)
        val occ = snap.state<CapabilityState.OccupancyReading>()!!
        assertEquals("Bedroom", occ.sensorName)
        assertFalse(occ.occupied)
        assertEquals(0, thermostat.unencryptedAccessoryRequests) // nothing but pairing travels in the clear
    }

    @Test
    fun capabilitiesComeFromTheAccessoryNotFromGuesses(): Unit = runBlocking {
        val id = paired()
        snapshot(id)
        val caps = adapter.capabilities(id)
        val th = caps.filterIsInstance<Capability.Thermostat>().single()
        assertEquals(setOf(HvacMode.OFF, HvacMode.HEAT, HvacMode.COOL, HvacMode.AUTO), th.modes)
        assertEquals(Celsius(7.0)..Celsius(26.0), th.heatRange)
        assertEquals(Celsius(18.0)..Celsius(35.0), th.coolRange)
        assertTrue(Capability.HumiditySensor in caps)
        assertTrue(caps.contains(Capability.OccupancySensor("Bedroom")))
    }

    @Test
    fun wrongCodeIsRefusedAndNothingIsStored(): Unit = runBlocking {
        findings = listOf(finding(thermostat))
        val d = adapter.discover().toList().single()
        assertIs<PatchbayError.Unauthorized>(adapter.pair(d, Prompt("314-15-927")).errorOrNull())
        assertFalse(thermostat.isPaired)
        assertIs<PatchbayError.InvalidArgument>(adapter.pair(d, Prompt("12")).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.pair(d, Prompt("123-45-678")).errorOrNull()) // HAP forbids trivial codes
    }

    @Test
    fun pairingCanBeCancelledAtTheFingerprintAndIsUndone(): Unit = runBlocking {
        findings = listOf(finding(thermostat))
        val d = adapter.discover().toList().single()
        val prompt = Prompt("31415926", trust = false)
        assertIs<PatchbayError.Cancelled>(adapter.pair(d, prompt).errorOrNull())
        assertNotNull(prompt.fingerprint)
        assertEquals(64, prompt.fingerprint!!.length)
        withTimeout(5_000) { while (thermostat.isPaired) kotlinx.coroutines.delay(20) } // the pairing was removed again
    }

    @Test
    fun anAlreadyPairedAccessoryExplainsWhatToDo(): Unit = runBlocking {
        paired()
        val second = HomeKitAdapter(NetGuard(), CastAdapter.CastFinder { findings }, HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000))
        val d = second.discover().toList().single()
        val e = second.pair(d, Prompt("314-15-926")).errorOrNull()
        assertIs<PatchbayError.Unsupported>(e)
        assertTrue("Apple Home" in e.message)
    }

    @Test
    fun modeAndSetpointCommands(): Unit = runBlocking {
        val id = paired(); snapshot(id)
        assertTrue(adapter.command(id, Command.SetSetpoints(Celsius(22.5), null)) is PatchbayResult.Ok)
        assertEquals(22.5, thermostat.targetTemp) // heat mode: the single target
        assertIs<PatchbayError.InvalidArgument>(adapter.command(id, Command.SetSetpoints(null, Celsius(25.0))).errorOrNull())

        assertTrue(adapter.command(id, Command.SetHvacMode(HvacMode.AUTO)) is PatchbayResult.Ok)
        assertEquals(3, thermostat.mode)
        assertTrue(adapter.command(id, Command.SetSetpoints(Celsius(18.0), Celsius(26.5))) is PatchbayResult.Ok)
        assertEquals(18.0, thermostat.heatThreshold); assertEquals(26.5, thermostat.coolThreshold)
        val auto = snapshot(id) { it.state<CapabilityState.ThermostatState>()?.mode == HvacMode.AUTO }.state<CapabilityState.ThermostatState>()!!
        assertEquals(Celsius(18.0), auto.heatSetpoint); assertEquals(Celsius(26.5), auto.coolSetpoint)

        assertTrue(adapter.command(id, Command.SetHvacMode(HvacMode.OFF)) is PatchbayResult.Ok)
        assertIs<PatchbayError.InvalidArgument>(adapter.command(id, Command.SetSetpoints(Celsius(20.0), null)).errorOrNull())
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.SetPower(true)).errorOrNull())
    }

    @Test
    fun aDifferentAccessoryAtTheSameAddressIsTrustChangedAndNeverSentCommands(): Unit = runBlocking {
        val id = paired()
        snapshot(id)
        // The address now belongs to another accessory with another identity (and its own keys).
        adapter.restore(id, other.address, null)
        // restore without a pairing keeps the stored keys but points at the new address:
        val first = withTimeout(10_000) { adapter.observe(id).first { it.reachability != Reachability.ONLINE } }
        assertEquals(Reachability.DEGRADED, first.reachability)
        assertTrue(adapter.trustChanged(id))
        assertEquals(HealthState.TRUST_CHANGED, adapter.health(id).state)
        assertIs<PatchbayError.TrustChanged>(adapter.command(id, Command.SetHvacMode(HvacMode.OFF)).errorOrNull())
        assertTrue(other.writes.isEmpty())
    }

    @Test
    fun goesOfflineAndComesBack(): Unit = runBlocking {
        val id = paired()
        snapshot(id)
        thermostat.close()
        val gone = withTimeout(15_000) { adapter.observe(id).first { it.reachability == Reachability.OFFLINE } }
        assertTrue(gone.states.isEmpty())
    }

    @Test
    fun removingTheDeviceMakesTheThermostatForgetUs(): Unit = runBlocking {
        val id = paired()
        snapshot(id)
        assertTrue(thermostat.isPaired)
        adapter.unpair(id)
        assertFalse(thermostat.isPaired) // so it can be paired again with the setup code
    }

    @Test
    fun aRestartRemembersThePortAndFindsANewOneWhenItChanges(): Unit = runBlocking {
        findings = listOf(finding(thermostat))
        val d = adapter.discover().toList().single()
        val p = adapter.pair(d, Prompt("314-15-926")).getOrNull()!!

        // A fresh adapter (a restart) gets the port from what was stored with the pairing.
        val restarted = HomeKitAdapter(NetGuard(), CastAdapter.CastFinder { findings }, HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000, pollIntervalMs = 100))
        restarted.restore(p.deviceId, d.address, p)
        withTimeout(10_000) { restarted.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }

        // The stored port is stale (the thermostat picked another after a reboot): the announced port is used.
        val stale = p.copy(secret = p.secret + byteArrayOf(5, 2, 0, 1)) // later TLV wins: port 1
        val third = HomeKitAdapter(NetGuard(), CastAdapter.CastFinder { findings }, HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000, pollIntervalMs = 100))
        third.restore(p.deviceId, d.address, stale)
        withTimeout(10_000) { third.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
        restarted.close(); third.close()
    }
}
