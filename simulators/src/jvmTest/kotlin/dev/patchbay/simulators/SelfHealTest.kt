package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.store.InMemoryHomeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A device that has moved while Patchbay was running comes back by itself, searches are rate-limited, and they never start before a device has been offline a while. */
class SelfHealTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private object Yes : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = null
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    private val castIds = mapOf("_googlecast._tcp" to "00112233445566778899aabbccddeeff")
    private fun finding(address: String, port: Int) = Finding(address, emptySet(), setOf("_googlecast._tcp"), setOf(port), "Living room TV", "Chromecast", null, null, castIds)

    @Test
    fun aDeviceThatMovedComesBackWithoutPressingScanAndSearchesAreRateLimited(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = CastSimulator("127.0.0.94", keystore("device-a.p12"), "testpass".toCharArray())
        val port = first.port
        var findings = listOf(finding("127.0.0.94", port))
        val searches = AtomicInteger()
        val guard = NetGuard()
        val adapter = CastAdapter(guard, { searches.incrementAndGet(); findings }, CastAdapter.Config(port = port, connectTimeoutMs = 1_500, heartbeatMs = 300, minEmitGapMs = 0, maxBackoffMs = 300))
        val t0 = 2_000_000_000_000L
        val home = LanHome(guard, listOf(adapter), scope, null, InMemoryHomeStore(), clock = { t0 })
        var second: CastSimulator? = null
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            withTimeout(10_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }
            val afterScan = searches.get()

            // It moves: the old address goes quiet, the TV reappears elsewhere.
            first.close()
            second = CastSimulator("127.0.0.95", keystore("device-a.p12"), "testpass".toCharArray(), port = port)
            second.volume = 0.61
            findings = listOf(finding("127.0.0.95", port))
            withTimeout(15_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.OFFLINE) delay(50) }

            home.tickHeal(t0 + 60_000) // offline for less than 90 s: not yet
            assertEquals(afterScan, searches.get())

            home.tickHeal(t0 + 100_000) // long enough: one quiet search, no manual scan
            assertEquals(afterScan + 1, searches.get())
            withTimeout(15_000) {
                while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE ||
                    home.devices.value.single().snapshot?.state<CapabilityState.VolumeState>()?.level != 61) { second.pushStatus(); delay(100) }
            }
            assertEquals("127.0.0.95", home.devices.value.single().address)

            // It answers again, so the back-off is forgotten and nothing more is searched for.
            home.tickHeal(t0 + 400_000)
            assertEquals(afterScan + 1, searches.get())
        } finally {
            second?.close(); first.close(); scope.cancel()
        }
    }

    @Test
    fun aDeviceThatStaysGoneIsSearchedForLessAndLessOften(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tv = CastSimulator("127.0.0.96", keystore("device-a.p12"), "testpass".toCharArray())
        val port = tv.port
        val searches = AtomicInteger()
        val guard = NetGuard()
        val adapter = CastAdapter(guard, { searches.incrementAndGet(); listOf(finding("127.0.0.96", port)) }, CastAdapter.Config(port = port, connectTimeoutMs = 1_000, heartbeatMs = 300, minEmitGapMs = 0, maxBackoffMs = 300))
        val t0 = 3_000_000_000_000L
        val home = LanHome(guard, listOf(adapter), scope, null, InMemoryHomeStore(), clock = { t0 })
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            withTimeout(10_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }
            tv.close() // gone for good
            withTimeout(15_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.OFFLINE) delay(50) }
            val base = searches.get()

            fun at(seconds: Int) = t0 + seconds * 1_000L
            home.tickHeal(at(100)); assertEquals(base + 1, searches.get())   // first search, next not before +120 s
            home.tickHeal(at(150)); assertEquals(base + 1, searches.get())
            home.tickHeal(at(230)); assertEquals(base + 2, searches.get())   // next not before +240 s
            home.tickHeal(at(400)); assertEquals(base + 2, searches.get())
            home.tickHeal(at(480)); assertEquals(base + 3, searches.get())
            home.tickHeal(at(960)); assertEquals(base + 4, searches.get())   // 480 s gap; next not before +960 s
            home.tickHeal(at(1_900)); assertEquals(base + 4, searches.get())
            home.tickHeal(at(1_920)); assertEquals(base + 5, searches.get()) // the gap would now double to 1920 s but is capped at 30 minutes (1800 s)
            home.tickHeal(at(3_700)); assertEquals(base + 5, searches.get()) // an uncapped doubling would also say "not yet" here...
            home.tickHeal(at(3_720)); assertEquals(base + 6, searches.get()) // ...but only the cap makes it due at 1920 + 1800 = 3720 s, not 1920 + 1920 = 3840 s
        } finally {
            tv.close(); scope.cancel()
        }
    }
}
