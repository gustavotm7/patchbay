package dev.patchbay.simulators

import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.webos.WebOsAdapter
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
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
import java.io.File
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Contract test: the real WebOsAdapter against a simulated TV over loopback TLS + WebSocket. */
class WebOsContractTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private val tv = WebOsSimulator("127.0.0.10", keystore("device-a.p12"), "testpass".toCharArray())
    private val guard = NetGuard()
    private val adapter = WebOsAdapter(
        guard,
        WebOsAdapter.Config(
            ssdpTargets = listOf("127.0.0.10"), ssdpPort = tv.ssdpPort, wssPort = tv.wssPort, scanWindowMs = 500,
            replyTimeoutMs = 2_000, pairingTimeoutMs = 3_000, pollIntervalMs = 100, maxBackoffMs = 300, bindLoopbackOnly = true,
        ),
    )

    private class RecordingPrompt(val trust: Boolean = true) : PairingPrompt {
        var fingerprint: String? = null
        val instructions = mutableListOf<String>()
        override suspend fun askForCode(label: String, length: IntRange): String? = null
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean { fingerprint = sha256; return trust }
        override suspend fun instruct(message: String) { instructions += message }
    }

    @AfterTest
    fun tearDown() {
        adapter.close(); tv.close()
    }

    private suspend fun discoverOne(): DiscoveredDevice = adapter.discover().toList().single()

    private suspend fun pairAndRestore(): dev.patchbay.core.model.DeviceId {
        val d = discoverOne()
        val p = adapter.pair(d, RecordingPrompt()).getOrNull()!!
        adapter.restore(p.deviceId, d.address, p)
        return p.deviceId
    }

    @Test
    fun discoversViaSsdp(): Unit = runBlocking {
        val d = discoverOne()
        assertEquals("127.0.0.10", d.address)
        assertEquals("lg-webos-4a3b-sim-tv", adapter.deviceId(d).value)
    }

    @Test
    fun pairingShowsFingerprintThenAsksUserToAcceptOnTv(): Unit = runBlocking {
        val d = discoverOne()
        val prompt = RecordingPrompt()
        val p = adapter.pair(d, prompt).getOrNull()
        assertNotNull(p)
        assertNotNull(prompt.fingerprint)
        assertEquals(p.pinnedFingerprint, prompt.fingerprint!!.replace(" ", ""))
        assertTrue(prompt.instructions.single().contains("TV"))
        assertTrue(p.secret.decodeToString() in tv.issuedKeys)
    }

    @Test
    fun rejectingTheFingerprintSendsNothing(): Unit = runBlocking {
        val d = discoverOne()
        assertIs<PatchbayError.Unauthorized>(adapter.pair(d, RecordingPrompt(trust = false)).errorOrNull())
        assertEquals(0, tv.issuedKeys.size)
        assertEquals(0, tv.requests.size)
    }

    @Test
    fun declinedOnTheTv(): Unit = runBlocking {
        tv.userAccepts = false
        val d = discoverOne()
        assertIs<PatchbayError.Unauthorized>(adapter.pair(d, RecordingPrompt()).errorOrNull())
    }

    @Test
    fun observesAndCommandsAfterPairing(): Unit = runBlocking {
        val id = pairAndRestore()
        val snap = withTimeout(5_000) { adapter.observe(id).first() }
        assertEquals(Reachability.ONLINE, snap.reachability)
        assertEquals(CapabilityState.VolumeState(14, false), snap.state<CapabilityState.VolumeState>())
        assertEquals(true, snap.state<CapabilityState.PowerState>()?.on)

        assertTrue(adapter.command(id, Command.SetVolume(22)) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.SetMute(true)) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.SelectInput("PS5")) is PatchbayResult.Ok)
        assertEquals(22, tv.volume)
        assertEquals(true, tv.muted)
        assertEquals("HDMI_3", tv.input)
        assertTrue(adapter.command(id, Command.SetPower(false)) is PatchbayResult.Ok)
        assertEquals(false, tv.on)
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.SetPower(true)).errorOrNull())
    }

    @Test
    fun changedTvKeyIsTrustChangedNotSilentlyAccepted(): Unit = runBlocking {
        // Paired with the real TV (key A)…
        val d = discoverOne()
        val pairing = adapter.pair(d, RecordingPrompt()).getOrNull()!!
        assertEquals(pinOf("device-a.p12"), pairing.pinnedFingerprint)
        // …then something else answers at the same address with key B (spoofed TV, or a factory reset).
        val impostor = WebOsSimulator("127.0.0.11", keystore("device-b.p12"), "testpass".toCharArray())
        impostor.issuedKeys += pairing.secret.decodeToString() // even if it "knows" our client key
        try {
            val a2 = WebOsAdapter(guard, WebOsAdapter.Config(wssPort = impostor.wssPort, replyTimeoutMs = 2_000, bindLoopbackOnly = true))
            a2.restore(pairing.deviceId, "127.0.0.11", pairing)
            assertIs<PatchbayError.TrustChanged>(a2.command(pairing.deviceId, Command.SetVolume(5)).errorOrNull())
            assertEquals(dev.patchbay.core.adapter.HealthState.TRUST_CHANGED, a2.health(pairing.deviceId).state)
            assertEquals(0, impostor.requests.size) // nothing, not even the client key, reached it
        } finally {
            impostor.close()
        }
    }

    private fun pinOf(name: String): String =
        dev.patchbay.core.net.tls.PinnedTls.spkiSha256(keystore(name).getCertificate("device") as java.security.cert.X509Certificate)
}
