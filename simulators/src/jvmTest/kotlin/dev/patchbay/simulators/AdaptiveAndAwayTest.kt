package dev.patchbay.simulators

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Adaptive lighting and away mode with a fixed clock (2 Nov 2026) and a seeded random. */
class AdaptiveAndAwayTest {
    private val utc = ZoneId.of("UTC")
    private val lampId = DeviceId("govee-1f80c5323236724e")
    private fun at(h: Int, m: Int, s: Int = 20): Long = ZonedDateTime.of(2026, 11, 2, h, m, s, 0, utc).toInstant().toEpochMilli()

    private class Rig(val home: LanHome, val close: () -> Unit)

    private fun rig(lamp: GoveeSimulator): Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.11"), listenPort = 0, bindLoopbackOnly = true,
                scanPort = lamp.scanPort, commandPort = lamp.commandPort, scanWindowMs = 400, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        lamp.replyPort = (adapter.start() as PatchbayResult.Ok).value
        return Rig(LanHome(guard, listOf(adapter), scope, null, zone = utc, random = Random(7))) { adapter.close(); scope.cancel() }
    }

    private suspend fun ready(r: Rig) {
        r.home.scan()
        repeat(60) { if (r.home.devices.value.singleOrNull()?.snapshot?.states?.isNotEmpty() == true) return else delay(50) }
    }

    @Test
    fun adaptiveMovesWhiteTowardTheCurveAndBacksOffWhenYouChangeIt(): Unit = runBlocking {
        val lamp = GoveeSimulator("127.0.0.11", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        lamp.on = true; lamp.kelvin = 2700
        val r = rig(lamp); ready(r)
        r.home.setAdaptive(lampId, true)
        assertTrue(lampId in r.home.adaptive.value)

        r.home.tickAdaptive(at(13, 0)) // midday: 5500 K
        delay(500); assertEquals(5500, lamp.kelvin)

        // The user picks a warm white by hand.
        lamp.kelvin = 3000
        delay(400)
        r.home.tickAdaptive(at(13, 5)) // a few seconds after our change we still looked; now notice the override
        r.home.tickAdaptive(at(13, 6))
        delay(400); assertEquals(3000, lamp.kelvin) // left alone

        // Off then on again hands control back.
        lamp.on = false; delay(400); r.home.tickAdaptive(at(13, 7))
        lamp.on = true; delay(400)
        r.home.tickAdaptive(at(20, 0)) // evening: 3000 K, already close (no change needed beyond 100 K)
        lamp.kelvin = 5000; delay(400)
        r.home.tickAdaptive(at(21, 0)) // target ~2700
        delay(500); assertTrue(lamp.kelvin < 3300, "kelvin was ${lamp.kelvin}")
        r.close(); lamp.close()
    }

    @Test
    fun coloursAndOffLightsAreNeverTouched(): Unit = runBlocking {
        val lamp = GoveeSimulator("127.0.0.11", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        lamp.on = false; lamp.kelvin = 2700
        val r = rig(lamp); ready(r)
        r.home.setAdaptive(lampId, true)
        r.home.tickAdaptive(at(13, 0))
        delay(400); assertEquals(2700, lamp.kelvin) // off: untouched
        lamp.on = true; lamp.kelvin = 0 // colour mode (no white temperature reported)
        delay(500)
        r.home.tickAdaptive(at(13, 1))
        delay(400); assertEquals(0, lamp.kelvin)
        r.home.setAdaptive(lampId, false)
        assertFalse(lampId in r.home.adaptive.value)
        r.close(); lamp.close()
    }

    @Test
    fun awayModeSwitchesEverythingOffThenImitatesPresenceInTheEvening(): Unit = runBlocking {
        val lamp = GoveeSimulator("127.0.0.11", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        lamp.on = true
        val r = rig(lamp); ready(r)
        r.home.setAway(true, setOf(lampId))
        delay(400); assertEquals(false, lamp.on)
        assertTrue(r.home.activity.value.any { it.deviceName == "Away mode" && it.event.startsWith("Turned on") })

        r.home.tickAway(at(17, 0)); delay(200); assertEquals(false, lamp.on) // too early
        r.home.tickAway(at(19, 0)) // schedules the first flip within 40 minutes
        delay(200); assertEquals(false, lamp.on)
        r.home.tickAway(at(19, 45)) // now due
        delay(500); assertEquals(true, lamp.on)
        assertTrue(r.home.activity.value.any { it.event.startsWith("Simulated presence") })
        r.home.tickAway(at(23, 0))
        delay(500); assertEquals(false, lamp.on)
        assertTrue(r.home.activity.value.any { it.event.contains("ended for the night") })

        // Turning it off stops everything.
        r.home.setAway(false, emptySet())
        lamp.on = false
        r.home.tickAway(at(20, 0)); r.home.tickAway(at(21, 0))
        delay(300); assertEquals(false, lamp.on)
        r.close(); lamp.close()
    }
}
