package dev.patchbay.simulators

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.home.ScanState
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Percent
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LanHomeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guard = NetGuard()
    private val lamp = GoveeSimulator("127.0.0.4", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
    private val adapter = GoveeLanAdapter(
        guard, scope,
        GoveeLanAdapter.Config(
            scanTargets = listOf("127.0.0.4"), listenPort = 0, bindLoopbackOnly = true,
            scanPort = lamp.scanPort, commandPort = lamp.commandPort,
            scanWindowMs = 500, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
        ),
    )
    private val home = LanHome(guard, listOf(adapter), scope, log = null)

    init {
        lamp.replyPort = (adapter.start() as PatchbayResult.Ok).value
    }

    @AfterTest
    fun tearDown() {
        adapter.close(); lamp.close(); scope.cancel()
    }

    @Test
    fun scanSendAndObserve(): Unit = runBlocking {
        assertEquals(1, (home.scan() as PatchbayResult.Ok).value)
        assertEquals(ScanState.DONE, home.scanState.value)
        val d = home.devices.value.single().device
        assertEquals("Govee H6076", d.name)

        assertTrue(home.send(d.id, Command.SetPower(false)) is PatchbayResult.Ok)
        // Optimistic update is immediate…
        assertEquals(false, home.devices.value.single().snapshot!!.state<CapabilityState.PowerState>()!!.on)
        // …and the device really changed.
        assertEquals(false, lamp.on)
        delay(300)
        assertEquals(false, home.devices.value.single().snapshot!!.state<CapabilityState.PowerState>()!!.on)
        assertTrue(guard.stats.value.allowed > 0)
        assertEquals(0, guard.stats.value.denied)
    }

    @Test
    fun invalidCommandsNeverReachTheDevice(): Unit = runBlocking {
        home.scan()
        val d = home.devices.value.single().device
        lamp.received.clear()
        assertIs<PatchbayError.InvalidArgument>(home.send(d.id, Command.SetBrightness(Percent(0.5))).errorOrNull())
        assertIs<PatchbayError.Unsupported>(home.send(d.id, Command.SetVolume(3)).errorOrNull())
        delay(150)
        assertTrue(lamp.received.none { "brightness" in it.second || "volume" in it.second })
    }
}
