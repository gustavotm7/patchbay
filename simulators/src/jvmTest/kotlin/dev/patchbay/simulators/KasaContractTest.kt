package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.kasa.KasaAdapter
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test: the real KasaAdapter against simulated plugs over real loopback UDP and TCP. */
class KasaContractTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guard = NetGuard()
    private lateinit var adapter: KasaAdapter
    private lateinit var lamp: KasaSimulator
    private lateinit var fan: KasaSimulator
    private lateinit var bulb: KasaSimulator
    private lateinit var strip: KasaSimulator

    private val lampKey = "80060000000000000000000000000000000000aa"
    private val fanKey = "80060000000000000000000000000000000000bb"
    private val lampId = KasaAdapter.deviceIdFor(lampKey)

    private val noPrompt = object : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = null
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    @BeforeTest
    fun setUp() {
        lamp = KasaSimulator("127.0.0.2", lampKey)
        fan = KasaSimulator("127.0.0.3", fanKey, model = "KP115(US)", alias = "Fan", port = lamp.port)
        bulb = KasaSimulator("127.0.0.4", "80060000000000000000000000000000000000cc", type = "IOT.SMARTBULB", port = lamp.port)
        strip = KasaSimulator("127.0.0.5", "80060000000000000000000000000000000000dd", model = "HS300(US)", withChildren = true, port = lamp.port)
        adapter = KasaAdapter(
            guard, scope,
            KasaAdapter.Config(
                scanTargets = listOf("127.0.0.2", "127.0.0.3", "127.0.0.4", "127.0.0.5"), port = lamp.port,
                scanWindowMs = 600, ioTimeoutMs = 500, pollIntervalMs = 100, maxBackoffMs = 400,
            ),
        )
    }

    @AfterTest
    fun tearDown() {
        listOf(lamp, fan, bulb, strip).forEach { it.close() }
        scope.cancel()
    }

    private suspend fun discoverAll() = adapter.discover().toList()

    @Test
    fun discoversPlugsAndSkipsBulbsAndStrips(): Unit = runBlocking {
        val found = discoverAll().associateBy { it.key }
        assertEquals(setOf(lampKey, fanKey), found.keys)
        val d = found.getValue(lampKey)
        assertEquals("127.0.0.2", d.address)
        assertEquals(DeviceKind.PLUG, d.kind)
        assertEquals("Desk lamp", d.displayName)
        assertEquals("KP115(US)", found.getValue(fanKey).model)
        // Bulbs and strips answered but are unsupported, not "malformed".
        assertEquals(0L, adapter.rejectedMessages)
    }

    @Test
    fun pairStoresNoSecret(): Unit = runBlocking {
        val d = discoverAll().first { it.key == lampKey }
        val p = adapter.pair(d, noPrompt).getOrNull()!!
        assertEquals(0, p.secret.size)
        assertEquals(lampId, p.deviceId)
    }

    @Test
    fun powerCommandsSwitchTheRightPlug(): Unit = runBlocking {
        discoverAll()
        assertTrue(adapter.command(lampId, Command.SetPower(true)) is PatchbayResult.Ok)
        assertEquals(true, lamp.on)
        assertEquals(false, fan.on)
        assertEquals(true, adapter.status(lampId).getOrNull()!!.relayOn)
        assertTrue(adapter.command(lampId, Command.SetPower(false)) is PatchbayResult.Ok)
        assertEquals(false, adapter.status(lampId).getOrNull()!!.relayOn)
    }

    @Test
    fun refusedCommandAndUnsupportedCommandsAreErrors(): Unit = runBlocking {
        discoverAll()
        lamp.errCode = -1
        assertIs<PatchbayError.Unreachable>(adapter.command(lampId, Command.SetPower(true)).errorOrNull())
        lamp.errCode = 0
        assertIs<PatchbayError.Unsupported>(adapter.command(lampId, Command.SetVolume(3)).errorOrNull())
        assertIs<PatchbayError.Unreachable>(adapter.command(DeviceId("kasa-unknown"), Command.SetPower(true)).errorOrNull())
    }

    @Test
    fun restoreWorksWithoutAScan(): Unit = runBlocking {
        assertTrue(adapter.restore(lampId, "127.0.0.2", null) is PatchbayResult.Ok)
        assertTrue(adapter.command(lampId, Command.SetPower(true)) is PatchbayResult.Ok)
        assertTrue(adapter.restore(lampId, "8.8.8.8", null) is PatchbayResult.Err) // NetGuard refuses non-LAN
    }

    @Test
    fun observeReportsOnlineThenBacksOffWhenOffline(): Unit = runBlocking {
        discoverAll()
        lamp.on = true
        val snaps = withTimeout(10_000) {
            val first = adapter.observe(lampId).take(1).toList().single()
            lamp.offline = true
            first to adapter.observe(lampId).take(3).toList()
        }
        assertEquals(Reachability.ONLINE, snaps.first.reachability)
        assertEquals(CapabilityState.PowerState(true), snaps.first.states.single())
        assertEquals(listOf(Reachability.DEGRADED, Reachability.DEGRADED, Reachability.OFFLINE), snaps.second.map { it.reachability })
    }

    @Test
    fun malformedAndOversizeRepliesAreRejectedNotCrashing(): Unit = runBlocking {
        discoverAll()
        lamp.malformedReplies = true
        assertTrue(adapter.status(lampId) is PatchbayResult.Err)
        lamp.malformedReplies = false
        lamp.oversizeFrame = true
        assertIs<PatchbayError.ProtocolViolation>(adapter.status(lampId).errorOrNull())
        lamp.oversizeFrame = false
        assertTrue(adapter.status(lampId) is PatchbayResult.Ok) // still works afterwards
        assertTrue(adapter.rejectedMessages >= 1)
    }
}
