package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.tuya.TuyaBulbProfile
import dev.patchbay.core.adapter.tuya.TuyaLocalAdapter
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Tuya adapter against simulated bulbs on protocols 3.3, 3.4 and 3.5. Pairing is not told which version a device speaks: it tries each and
 * keeps the one that answers, and later reads and writes use that version. The simulators have their own framing, HMAC and AES code.
 */
class TuyaSecureVersionsTest {
    private val devId = "bf1234567890abcdef12"
    private val key = "0123456789abcdef"

    private class Prompt(val key: String?) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = key
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    private class Rig(version: String, address: String) : AutoCloseable {
        val bulb = TuyaSimulator(address, "bf1234567890abcdef12", "0123456789abcdef", version = version)
        val adapter = TuyaLocalAdapter(NetGuard(), TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        val address = address
        override fun close() = bulb.close()
    }

    private suspend fun pair(r: Rig, key: String = this.key): PatchbayResult<dev.patchbay.core.adapter.Pairing> {
        val d = r.adapter.manualDevice(r.address, devId, TuyaBulbProfile.V2.label).getOrNull()!!
        return r.adapter.pair(d, Prompt(key))
    }

    private suspend fun paired(r: Rig): DeviceId {
        val p = pair(r).getOrNull()!!
        r.adapter.restore(p.deviceId, r.address, p)
        return p.deviceId
    }

    @Test
    fun pairingFindsTheVersionTheDeviceSpeaksAndRemembersIt(): Unit = runBlocking {
        for ((version, address) in listOf("3.3" to "127.0.0.61", "3.4" to "127.0.0.62", "3.5" to "127.0.0.63")) {
            Rig(version, address).use { r ->
                val p = pair(r).getOrNull()!!
                assertEquals(listOf(TuyaBulbProfile.V2.id, key, version), p.secret.decodeToString().split('\n'), "the secret records the version ($version)")
                r.adapter.restore(p.deviceId, r.address, p)
                assertContains(r.adapter.diagnostics(p.deviceId).first(), "protocol spoken: $version")
            }
        }
    }

    @Test
    fun readsAndWritesWorkOnEveryVersion(): Unit = runBlocking {
        for ((version, address) in listOf("3.3" to "127.0.0.64", "3.4" to "127.0.0.65", "3.5" to "127.0.0.66")) {
            Rig(version, address).use { r ->
                val id = paired(r)
                val snap = withTimeout(5_000) { r.adapter.observe(id).first { it.reachability == Reachability.ONLINE } }
                assertEquals(CapabilityState.PowerState(true), snap.state<CapabilityState.PowerState>(), version)

                assertTrue(r.adapter.command(id, Command.SetPower(false)) is PatchbayResult.Ok, version)
                assertEquals(JsonPrimitive(false), r.bulb.dps["20"], version)
                assertEquals(listOf(mapOf("20" to JsonPrimitive(false))), r.bulb.writes, version)
                val after = withTimeout(5_000) { r.adapter.observe(id).first { it.reachability == Reachability.ONLINE } }
                assertEquals(CapabilityState.PowerState(false), after.state<CapabilityState.PowerState>(), version)
            }
        }
    }

    @Test
    fun everyConnectionAgreesItsOwnSessionKey(): Unit = runBlocking {
        Rig("3.4", "127.0.0.67").use { r ->
            val id = paired(r)
            val before = r.bulb.sessionsNegotiated
            r.adapter.command(id, Command.SetPower(true)); r.adapter.command(id, Command.SetPower(false))
            assertEquals(before + 2, r.bulb.sessionsNegotiated)
        }
    }

    @Test
    fun aWrongKeyIsRefusedOnEveryVersionWithAHelpfulMessage(): Unit = runBlocking {
        for ((version, address) in listOf("3.3" to "127.0.0.68", "3.4" to "127.0.0.69", "3.5" to "127.0.0.70")) {
            Rig(version, address).use { r ->
                val e = pair(r, "fedcba9876543210").errorOrNull()
                assertIs<PatchbayError.Unauthorized>(e, version)
                assertTrue(r.bulb.writes.isEmpty())
            }
        }
    }

    @Test
    fun aDeviceThatSendsTheVersionHeaderInRepliesIsStillRead(): Unit = runBlocking {
        for ((version, address) in listOf("3.4" to "127.0.0.71", "3.5" to "127.0.0.72")) {
            Rig(version, address).use { r ->
                val id = paired(r)
                r.bulb.versionHeaderInQuery = true
                val snap = withTimeout(5_000) { r.adapter.observe(id).first { it.reachability == Reachability.ONLINE } }
                assertEquals(CapabilityState.PowerState(true), snap.state<CapabilityState.PowerState>(), version)
            }
        }
    }

    @Test
    fun damagedOrOversizeRepliesAreRejectedNotCrashingOnEveryVersion(): Unit = runBlocking {
        for ((version, address) in listOf("3.4" to "127.0.0.73", "3.5" to "127.0.0.74")) {
            Rig(version, address).use { r ->
                val id = paired(r)
                r.bulb.badChecksum = true
                assertIs<PatchbayError.ProtocolViolation>(r.adapter.command(id, Command.SetPower(true)).errorOrNull(), "$version checksum")
                r.bulb.badChecksum = false
                r.bulb.oversizeLength = true
                assertIs<PatchbayError.ProtocolViolation>(r.adapter.command(id, Command.SetPower(true)).errorOrNull(), "$version length")
                r.bulb.oversizeLength = false
                r.bulb.garbage = true
                assertIs<PatchbayError.ProtocolViolation>(r.adapter.command(id, Command.SetPower(true)).errorOrNull(), "$version garbage")
                r.bulb.garbage = false
                assertTrue(r.adapter.rejectedMessages >= 3, version)
                assertTrue(r.adapter.command(id, Command.SetPower(true)) is PatchbayResult.Ok, "$version still fine afterwards")
            }
        }
    }

    @Test
    fun aDeviceThatHangsUpIsUnreachableAndNeverWritten(): Unit = runBlocking {
        for ((version, address) in listOf("3.4" to "127.0.0.75", "3.5" to "127.0.0.76")) {
            Rig(version, address).use { r ->
                val id = paired(r)
                r.bulb.offline = true
                val e = r.adapter.command(id, Command.SetPower(false)).errorOrNull()
                assertTrue(e is PatchbayError.Unreachable || e is PatchbayError.Timeout, "$version: $e")
                assertTrue(r.bulb.writes.isEmpty())
            }
        }
    }

    @Test
    fun pairsLearnProfileOnNewerVersionsToo(): Unit = runBlocking {
        Rig("3.5", "127.0.0.77").use { r ->
            val d = r.adapter.manualDevice(r.address, devId, dev.patchbay.core.adapter.tuya.TuyaLearnProfile.label).getOrNull()!!
            val p = r.adapter.pair(d, Prompt(key)).getOrNull()!!
            r.adapter.restore(p.deviceId, r.address, p)
            val snap = withTimeout(5_000) { r.adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            assertTrue("20" in snap.state<CapabilityState.DiagnosticValues>()!!.values)
        }
    }

    @Test
    fun secretsSavedBeforeTheseVersionsExistedAreReadAs33(): Unit = runBlocking {
        Rig("3.3", "127.0.0.78").use { r ->
            val old = dev.patchbay.core.adapter.Pairing(DeviceId("tuya-$devId"), "${TuyaBulbProfile.V2.id}\n$key".toByteArray(), null)
            r.adapter.restore(old.deviceId, r.address, old)
            assertContains(r.adapter.diagnostics(old.deviceId).first(), "protocol spoken: 3.3")
            assertTrue(r.adapter.command(old.deviceId, Command.SetPower(false)) is PatchbayResult.Ok)
        }
    }
}
