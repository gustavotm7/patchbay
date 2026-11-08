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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSceneTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private object Yes : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = null
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun aSceneRestoresTvVolumeAndMuteButNothingElse(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tv = CastSimulator("127.0.0.91", keystore("device-a.p12"), "testpass".toCharArray())
        val finding = Finding("127.0.0.91", emptySet(), setOf("_googlecast._tcp"), setOf(tv.port), "Living room TV", "Chromecast", null)
        val guard = NetGuard()
        val adapter = CastAdapter(guard, { listOf(finding) }, CastAdapter.Config(port = tv.port, connectTimeoutMs = 1_500, heartbeatMs = 300, minEmitGapMs = 0, maxBackoffMs = 300))
        val home = LanHome(guard, listOf(adapter), scope, null, InMemoryHomeStore())
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            suspend fun volumeIs(level: Int, muted: Boolean) = withTimeout(10_000) {
                while (home.devices.value.single().snapshot?.state<CapabilityState.VolumeState>().let { it?.level != level || it.muted != muted }) delay(50)
            }
            withTimeout(10_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }

            tv.volume = 0.15; tv.muted = false; tv.pushStatus()
            volumeIs(15, false)
            assertTrue(home.saveScene("Quiet night", listOf(id)) is PatchbayResult.Ok)

            tv.volume = 0.8; tv.muted = true; tv.pushStatus()
            volumeIs(80, true)
            assertTrue(home.runScene(home.scenes.value.single().id) is PatchbayResult.Ok)
            withTimeout(10_000) { while (tv.volume != 0.15 || tv.muted) delay(50) }
            assertEquals(0.15, tv.volume)
        } finally {
            tv.close(); scope.cancel()
        }
    }
}
