package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.tuya.TuyaBulbProfile
import dev.patchbay.core.adapter.tuya.TuyaLearnProfile
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
 * Two reply habits of real Tuya devices that the tinytuya library documents: an empty acknowledgement frame before the data of a query, and "device22" devices
 * (often with 22-character ids) that answer the normal query with "data unvalid" and must be asked for their data points by number.
 */
class TuyaQuirksTest {
    private val devId = "ebf5889331f7b82250fasi" // 22 characters, like the vacuum in docs/TUYA_KEYS.md
    private val key = "0123456789abcdef"

    private class Prompt(val key: String) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = key
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    private inner class Rig(version: String, val address: String) : AutoCloseable {
        val bulb = TuyaSimulator(address, devId, key, version = version)
        val adapter = TuyaLocalAdapter(NetGuard(), TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        override fun close() = bulb.close()

        suspend fun pair(label: String = TuyaBulbProfile.V2.label, withKey: String = key): PatchbayResult<dev.patchbay.core.adapter.Pairing> {
            val d = adapter.manualDevice(address, devId, label).getOrNull()!!
            return adapter.pair(d, Prompt(withKey))
        }

        suspend fun paired(label: String = TuyaBulbProfile.V2.label): DeviceId {
            val p = pair(label).getOrNull()!!
            adapter.restore(p.deviceId, address, p)
            return p.deviceId
        }

        suspend fun online(id: DeviceId) = withTimeout(8_000) { adapter.observe(id).first { it.reachability == Reachability.ONLINE } }
    }

    @Test
    fun emptyAcknowledgementsBeforeTheDataAreSkippedOnEveryVersion(): Unit = runBlocking {
        for ((version, address) in listOf("3.3" to "127.0.0.87", "3.4" to "127.0.0.88", "3.5" to "127.0.0.89")) {
            Rig(version, address).use { r ->
                r.bulb.emptyAcksBeforeData = 2
                val id = r.paired()
                assertEquals(CapabilityState.PowerState(true), r.online(id).state<CapabilityState.PowerState>(), version)
                assertTrue(r.adapter.command(id, Command.SetPower(false)) is PatchbayResult.Ok, version) // a control reply is the first frame, however many acks a query gets
                assertEquals(JsonPrimitive(false), r.bulb.dps["20"], version)
            }
        }
    }

    @Test
    fun aDeviceThatNeverSendsDataIsGivenUpOnNotWaitedForForever(): Unit = runBlocking {
        Rig("3.3", "127.0.0.90").use { r ->
            r.bulb.emptyAcksBeforeData = 12
            val e = withTimeout(15_000) { r.pair().errorOrNull() }
            assertIs<PatchbayError>(e)
            assertTrue(r.adapter.rejectedMessages > 0)
        }
    }

    @Test
    fun aDeviceThatSaysDataUnvalidIsReadByDataPointNumberWhateverReturnCodeItUses(): Unit = runBlocking {
        for (code in listOf(0, 1)) {
            Rig("3.3", "127.0.0.91").use { r ->
                r.bulb.device22 = true; r.bulb.device22Retcode = code
                val id = r.paired()
                assertContains(r.adapter.diagnostics(id).first(), "query style: by data point number")
                assertEquals(CapabilityState.PowerState(true), r.online(id).state<CapabilityState.PowerState>(), "return code $code")

                // Having found out, it asks the new way straight away: the normal query was only ever tried once.
                val normal = r.bulb.normalQueries.get()
                repeat(3) { r.online(id) }
                assertEquals(normal, r.bulb.normalQueries.get(), "return code $code")
                assertTrue(r.bulb.numberQueries.get() >= 4)

                // Writing is unaffected.
                assertTrue(r.adapter.command(id, Command.SetPower(false)) is PatchbayResult.Ok)
                assertEquals(JsonPrimitive(false), r.bulb.dps["20"])
            }
        }
    }

    @Test
    fun theReadOnlyProfileFindsTheDataPointsByRangesAndThenAsksOnlyForThose(): Unit = runBlocking {
        Rig("3.3", "127.0.0.92").use { r ->
            r.bulb.device22 = true
            r.bulb.dps.clear()
            r.bulb.dps["1"] = JsonPrimitive(true); r.bulb.dps["3"] = JsonPrimitive("standby"); r.bulb.dps["101"] = JsonPrimitive(50); r.bulb.dps["104"] = JsonPrimitive(88)
            val id = r.paired(TuyaLearnProfile.label)
            val shown = r.online(id).state<CapabilityState.DiagnosticValues>()!!.values
            assertEquals(setOf("1", "3", "101", "104"), shown.keys)
            // The search took one request per group of numbers; after that a reading is a single request for the numbers that answered.
            val before = r.bulb.numberQueries.get()
            r.online(id)
            assertTrue(r.bulb.numberQueries.get() - before in 1..2, "later readings ask once, not once per range (${r.bulb.numberQueries.get() - before})")
        }
    }

    @Test
    fun aWrongKeyOnADevice22DeviceIsStillRefusedAsAWrongKey(): Unit = runBlocking {
        Rig("3.3", "127.0.0.93").use { r ->
            r.bulb.device22 = true
            assertIs<PatchbayError.Unauthorized>(r.pair(withKey = "fedcba9876543210").errorOrNull())
        }
    }

    @Test
    fun aNormalDeviceIsNeverAskedByNumber(): Unit = runBlocking {
        Rig("3.3", "127.0.0.94").use { r ->
            val id = r.paired()
            r.online(id)
            assertEquals(0, r.bulb.numberQueries.get())
            assertTrue(r.adapter.diagnostics(id).first().endsWith("query style: normal"))
        }
    }
}
