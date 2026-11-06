package dev.patchbay.simulators

import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.tuya.TuyaBulbProfile
import dev.patchbay.core.adapter.tuya.TuyaLocalAdapter
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test: the real TuyaLocalAdapter against a simulated Tuya bulb (protocol 3.3) over loopback TCP. */
class TuyaContractTest {

    private val devId = "bf1234567890abcdef12"
    private val key = "0123456789abcdef"
    private val bulb = TuyaSimulator("127.0.0.50", devId, key)
    private val adapter = TuyaLocalAdapter(NetGuard(), TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))

    private class Prompt(val key: String?) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = key
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    @AfterTest
    fun tearDown() = bulb.close()

    private fun found(profile: TuyaBulbProfile = TuyaBulbProfile.V2, id: String = devId): DiscoveredDevice =
        adapter.manualDevice("127.0.0.50", id, profile.label).getOrNull()!!

    private suspend fun paired(): dev.patchbay.core.model.DeviceId {
        val p = adapter.pair(found(), Prompt(key)).getOrNull()!!
        adapter.restore(p.deviceId, "127.0.0.50", p)
        return p.deviceId
    }

    @Test
    fun manualAddValidatesEverything() {
        val d = found()
        assertEquals("tuya-$devId", adapter.deviceId(d).value)
        assertEquals("bulb-v2", d.model)
        assertEquals(listOf("Bulb (standard)", "Bulb (older)"), adapter.manualAdd.choices)
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.50", "short", "Bulb (standard)").errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.50", devId, "Vacuum").errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.50", devId, null).errorOrNull())
        assertIs<PatchbayError.NetGuardDenied>(adapter.manualDevice("8.8.8.8", devId, "Bulb (standard)").errorOrNull())
        assertTrue(adapter.manualDevice("bulb.local", devId, "Bulb (standard)") is PatchbayResult.Err)
        assertEquals(0, runBlocking { adapter.discover().toList().size })
    }

    @Test
    fun pairsWithTheRightKeyAndStoresProfileAndKeyAsTheSecret(): Unit = runBlocking {
        val p = adapter.pair(found(), Prompt(key)).getOrNull()!!
        assertEquals("bulb-v2\n$key", p.secret.decodeToString())
        assertFalse(p.toString().contains(key), "Pairing.toString must redact the secret")
    }

    @Test
    fun wrongKeyIsUnauthorizedAndMalformedKeysNeverReachTheNetwork(): Unit = runBlocking {
        assertIs<PatchbayError.Unauthorized>(adapter.pair(found(), Prompt("fedcba9876543210")).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.pair(found(), Prompt("short")).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.pair(found(), Prompt("has space 123456")).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.pair(found(), Prompt(null)).errorOrNull())
    }

    @Test
    fun wrongProfileIsExplainedNotSilentlyAccepted(): Unit = runBlocking {
        // The V1 layout expects data point 1 for power; this bulb is V2.
        assertIs<PatchbayError.Unsupported>(adapter.pair(found(TuyaBulbProfile.V1), Prompt(key)).errorOrNull())
    }

    @Test
    fun observeReadsAllowlistedStateFromTheDevice(): Unit = runBlocking {
        val id = paired()
        val snap = withTimeout(5_000) { adapter.observe(id).first { it.reachability == Reachability.ONLINE } }
        assertEquals(CapabilityState.PowerState(true), snap.state<CapabilityState.PowerState>())
        assertEquals(CapabilityState.ColourTemperatureState(Kelvin(4600)), snap.state<CapabilityState.ColourTemperatureState>())
    }

    @Test
    fun commandsWriteOnlyAllowlistedDataPoints(): Unit = runBlocking {
        val id = paired()
        assertTrue(adapter.command(id, Command.SetPower(false)) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.SetBrightness(Percent(100.0))) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.SetColour(Rgb(0, 255, 0))) is PatchbayResult.Ok)
        assertEquals(JsonPrimitive(false), bulb.dps["20"])
        assertEquals(JsonPrimitive(1000), bulb.dps["22"])
        assertEquals(JsonPrimitive("colour"), bulb.dps["21"])
        assertEquals(JsonPrimitive("007803e803e8"), bulb.dps["24"])
        val allowed = setOf("20", "21", "22", "23", "24")
        assertTrue(bulb.writes.all { it.keys.all { k -> k in allowed } }, bulb.writes.toString())
        assertEquals(3, bulb.writes.size)
        val state = adapter.observe(id).take(1).toList().single()
        assertEquals(CapabilityState.RgbState(Rgb(0, 255, 0)), state.state<CapabilityState.RgbState>())
    }

    @Test
    fun unsupportedCommandsNeverReachTheDevice(): Unit = runBlocking {
        val id = paired()
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.SetVolume(3)).errorOrNull())
        assertTrue(bulb.writes.isEmpty())
    }

    @Test
    fun noCommandsWithoutTheKey(): Unit = runBlocking {
        val d = found()
        adapter.restore(adapter.deviceId(d), "127.0.0.50", null)
        assertIs<PatchbayError.Unauthorized>(adapter.command(adapter.deviceId(d), Command.SetPower(true)).errorOrNull())
    }

    @Test
    fun toleratesAVersionHeaderOnQueryReplies(): Unit = runBlocking {
        bulb.versionHeaderInQuery = true
        assertTrue(adapter.pair(found(), Prompt(key)) is PatchbayResult.Ok)
    }

    @Test
    fun restoreAfterRestartKeepsTheProfileAndKey(): Unit = runBlocking {
        val p = adapter.pair(found(), Prompt(key)).getOrNull()!!
        val fresh = TuyaLocalAdapter(NetGuard(), TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100))
        assertTrue(fresh.restore(p.deviceId, "127.0.0.50", p) is PatchbayResult.Ok)
        assertTrue(fresh.command(p.deviceId, Command.SetPower(false)) is PatchbayResult.Ok)
        assertEquals(JsonPrimitive(false), bulb.dps["20"])
    }

    @Test
    fun observeBacksOffWhenOffline(): Unit = runBlocking {
        val id = paired()
        val snaps = withTimeout(10_000) {
            val first = adapter.observe(id).take(1).toList().single()
            bulb.offline = true
            first to adapter.observe(id).take(3).toList()
        }
        assertEquals(Reachability.ONLINE, snaps.first.reachability)
        assertEquals(listOf(Reachability.DEGRADED, Reachability.DEGRADED, Reachability.OFFLINE), snaps.second.map { it.reachability })
    }

    @Test
    fun badChecksumOversizeAndGarbageAreRejectedNotCrashing(): Unit = runBlocking {
        val id = paired()
        bulb.badChecksum = true
        assertIs<PatchbayError.ProtocolViolation>(adapter.command(id, Command.SetPower(true)).errorOrNull())
        bulb.badChecksum = false
        bulb.oversizeLength = true
        assertIs<PatchbayError.ProtocolViolation>(adapter.command(id, Command.SetPower(true)).errorOrNull())
        bulb.oversizeLength = false
        bulb.garbage = true
        assertIs<PatchbayError.ProtocolViolation>(adapter.command(id, Command.SetPower(true)).errorOrNull())
        assertTrue(adapter.rejectedMessages >= 3)
        bulb.garbage = false
        assertTrue(adapter.command(id, Command.SetPower(true)) is PatchbayResult.Ok) // still fine afterwards
    }
}
