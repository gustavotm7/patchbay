package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.govee.GoveeLanAdapter
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test: the real GoveeLanAdapter against simulated devices over real loopback UDP. */
class GoveeContractTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guard = NetGuard()
    private lateinit var adapter: GoveeLanAdapter
    private lateinit var lamp: GoveeSimulator
    private lateinit var strip: GoveeSimulator

    private val noPrompt = object : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = null
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    @BeforeTest
    fun setUp() {
        // Simulators first (ephemeral ports, shared by both via separate loopback IPs), then the adapter.
        lamp = GoveeSimulator("127.0.0.2", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        strip = GoveeSimulator("127.0.0.3", "AA:BB:CC:DD:EE:FF:00:11", "H619A", replyPort = 0, lamp.scanPort, lamp.commandPort)
        adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.2", "127.0.0.3"), listenPort = 0, bindLoopbackOnly = true,
                scanPort = lamp.scanPort, commandPort = lamp.commandPort,
                scanWindowMs = 600, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 50, maxBackoffMs = 400,
            ),
        )
        val listen = (adapter.start() as PatchbayResult.Ok).value
        lamp.replyPort = listen
        strip.replyPort = listen
    }

    @AfterTest
    fun tearDown() {
        adapter.close()
        lamp.close()
        strip.close()
        scope.cancel()
    }

    private suspend fun discoverAll() = adapter.discover().toList()

    @Test
    fun discoversBothDevicesAtTheirSourceAddress(): Unit = runBlocking {
        val found = discoverAll().associateBy { it.key }
        assertEquals(setOf("1f80c5323236724e", "aabbccddeeff0011"), found.keys)
        assertEquals("127.0.0.2", found.getValue("1f80c5323236724e").address)
        assertEquals("H619A", found.getValue("aabbccddeeff0011").model)
    }

    @Test
    fun pairStoresNoSecret(): Unit = runBlocking {
        val d = discoverAll().first { it.key == "1f80c5323236724e" }
        val p = adapter.pair(d, noPrompt).getOrNull()!!
        assertEquals(0, p.secret.size)
        assertEquals(GoveeLanAdapter.deviceIdFor(d.key), p.deviceId)
    }

    @Test
    fun commandsChangeDeviceState(): Unit = runBlocking {
        discoverAll()
        val id = GoveeLanAdapter.deviceIdFor("1f80c5323236724e")

        assertTrue(adapter.command(id, Command.SetPower(false)) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.SetBrightness(Percent(30.0))) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.SetColour(Rgb(46, 196, 182))) is PatchbayResult.Ok)
        val s1 = adapter.status(id).getOrNull()!!
        assertEquals(false, s1.on)
        assertEquals(30.0, s1.brightness.value)
        assertEquals(Rgb(46, 196, 182), s1.colour)
        assertEquals(null, s1.kelvin)

        assertTrue(adapter.command(id, Command.SetColourTemperature(Kelvin(4000))) is PatchbayResult.Ok)
        assertEquals(Kelvin(4000), adapter.status(id).getOrNull()!!.kelvin)

        // The other device is untouched.
        assertEquals(true, strip.on)
    }

    @Test
    fun unsupportedCommandsAreRefused(): Unit = runBlocking {
        discoverAll()
        val id = GoveeLanAdapter.deviceIdFor("1f80c5323236724e")
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.SetVolume(3)).errorOrNull())
    }

    @Test
    fun observeReportsOnlineThenBacksOffWhenOffline(): Unit = runBlocking {
        discoverAll()
        val id = GoveeLanAdapter.deviceIdFor("1f80c5323236724e")
        val snaps = withTimeout(10_000) {
            val first = adapter.observe(id).take(1).toList().single()
            lamp.offline = true
            first to adapter.observe(id).take(3).toList()
        }
        assertEquals(Reachability.ONLINE, snaps.first.reachability)
        assertTrue(snaps.first.states.any { it is CapabilityState.PowerState })
        assertEquals(listOf(Reachability.DEGRADED, Reachability.DEGRADED, Reachability.OFFLINE), snaps.second.map { it.reachability })
    }

    @Test
    fun malformedRepliesAreRejectedNotCrashing(): Unit = runBlocking {
        discoverAll()
        val id = GoveeLanAdapter.deviceIdFor("1f80c5323236724e")
        lamp.malformedReplies = true
        assertIs<PatchbayError.Timeout>(adapter.status(id).errorOrNull())
        assertTrue(adapter.rejectedMessages >= 1)
        lamp.malformedReplies = false
        assertTrue(adapter.status(id) is PatchbayResult.Ok) // still works afterwards
    }

    @Test
    fun forgedStatusFromAnotherAddressIsIgnored(): Unit = runBlocking {
        discoverAll()
        val id = GoveeLanAdapter.deviceIdFor("1f80c5323236724e")
        lamp.offline = true
        // An attacker on another LAN address sends a "status" for the lamp while we wait for it.
        val forger = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.9"), 0))
        val forged = """{"msg":{"cmd":"devStatus","data":{"onOff":1,"brightness":100}}}""".toByteArray()
        val job = launch(Dispatchers.IO) {
            repeat(5) {
                forger.send(DatagramPacket(forged, forged.size, InetSocketAddress(InetAddress.getLoopbackAddress(), adapterListenPort())))
                delay(50)
            }
        }
        assertIs<PatchbayError.Timeout>(adapter.status(id).errorOrNull())
        job.join()
        forger.close()
    }

    @Test
    fun commandsToOneDeviceArePaced(): Unit = runBlocking {
        discoverAll()
        val id = GoveeLanAdapter.deviceIdFor("1f80c5323236724e")
        lamp.received.clear()
        repeat(4) { adapter.command(id, Command.SetPower(it % 2 == 0)) }
        delay(200)
        val times = lamp.received.map { it.first }
        assertEquals(4, times.size)
        val gapsMs = times.zipWithNext { a, b -> (b - a) / 1_000_000 }
        assertTrue(gapsMs.all { it >= 45 }, "gaps were $gapsMs")
    }

    private fun adapterListenPort(): Int = lamp.replyPort
}
