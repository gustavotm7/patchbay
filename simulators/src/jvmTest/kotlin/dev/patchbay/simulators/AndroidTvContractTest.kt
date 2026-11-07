package dev.patchbay.simulators

import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.HealthState
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.androidtv.AndroidTvRemoteAdapter
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.MediaAction
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.model.RemoteKey
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test: the real AndroidTvRemoteAdapter against a simulated Google TV (independent protobuf and hash maths) over loopback TLS. */
class AndroidTvContractTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private val tv = AndroidTvSimulator("127.0.0.95", keystore("tv-a.p12"), "testpass".toCharArray())
    private val finding = Finding("127.0.0.95", emptySet(), setOf("_androidtvremote2._tcp"), setOf(tv.remotePort), "Living room TV", "Chromecast", null)
    private fun adapter(sim: AndroidTvSimulator = tv) = AndroidTvRemoteAdapter(
        NetGuard(), CastAdapter.CastFinder { listOf(finding, Finding("127.0.0.96", emptySet(), setOf("_googlecast._tcp"), emptySet(), "Cast only", null, null)) },
        AndroidTvRemoteAdapter.Config(pairingPort = sim.pairingPort, remotePort = sim.remotePort, connectTimeoutMs = 3_000, silentMs = 600, maxBackoffMs = 300),
    )
    private val adapter = adapter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Types whatever code the TV is showing, or a chosen one. */
    private class Prompt(val sim: AndroidTvSimulator, val trust: Boolean = true, val code: ((String) -> String?)? = { it }) : PairingPrompt {
        var fingerprint: String? = null
        override suspend fun askForCode(label: String, length: IntRange): String? = code?.invoke(sim.displayedCode ?: "000000")
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean { fingerprint = sha256; return trust }
        override suspend fun instruct(message: String) {}
    }

    @AfterTest fun tearDown() { scope.cancel(); tv.close() }

    private suspend fun found(): DiscoveredDevice = adapter.discover().toList().single()

    private suspend fun pairedId(a: AndroidTvRemoteAdapter = adapter): DeviceId {
        val d = a.discover().toList().single()
        val p = a.pair(d, Prompt(tv)).getOrNull()!!
        a.restore(p.deviceId, d.address, p)
        return p.deviceId
    }

    @Test
    fun discoversOnlyAndroidTvRemoteServices(): Unit = runBlocking {
        val d = found()
        assertEquals("Living room TV remote", d.displayName)
        assertEquals(DeviceKind.MEDIA_RECEIVER, d.kind)
        assertEquals("atvremote-ip-127-0-0-95", adapter.deviceId(d).value)
    }

    @Test
    fun pairsWithTheCodeTheTvShowsAndStoresTheClientIdentity(): Unit = runBlocking {
        val prompt = Prompt(tv)
        val p = adapter.pair(found(), prompt).getOrNull()!!
        assertTrue(p.secret.size > 1_000, "the stored secret holds the client certificate and key")
        assertEquals(64, p.pinnedFingerprint?.length)
        assertEquals(p.pinnedFingerprint, prompt.fingerprint?.replace(" ", ""))
    }

    @Test
    fun aWrongCodeIsRejectedAndCancellingIsQuiet(): Unit = runBlocking {
        val wrong = adapter.pair(found(), Prompt(tv, code = { shown -> "%02X".format((shown.take(2).toInt(16) + 1) and 0xFF) + shown.drop(2) })).errorOrNull()
        assertIs<PatchbayError.Unauthorized>(wrong)
        assertTrue(wrong.message.contains("code isn't right"))
        assertIs<PatchbayError.Cancelled>(adapter.pair(found(), Prompt(tv, trust = false)).errorOrNull())
        assertIs<PatchbayError.Cancelled>(adapter.pair(found(), Prompt(tv, code = null)).errorOrNull())
        assertIs<PatchbayError.Unauthorized>(adapter.pair(found(), Prompt(tv, code = { "12345" })).errorOrNull())
    }

    @Test
    fun theSessionStartsAnswersPingsAndDeliversKeyPresses(): Unit = runBlocking {
        val id = pairedId()
        val snaps = CopyOnWriteArrayList<dev.patchbay.core.model.DeviceSnapshot>()
        scope.launch { adapter.observe(id).collect { snaps += it } }
        withTimeout(8_000) { while (snaps.none { it.reachability == Reachability.ONLINE }) delay(30) }
        withTimeout(5_000) { while (tv.pongs < 2) delay(30) } // pings from the TV are answered
        assertTrue(adapter.command(id, Command.PressKey(RemoteKey.UP)) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.PressKey(RemoteKey.HOME)) is PatchbayResult.Ok)
        assertTrue(adapter.command(id, Command.PressKey(RemoteKey.VOLUME_DOWN)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (tv.keys.size < 3) delay(30) }
        assertEquals(listOf(19 to 3, 3 to 3, 25 to 3), tv.keys.toList()) // DPAD_UP, HOME, VOLUME_DOWN, each a SHORT press
    }

    @Test
    fun otherCommandsAreRefusedAndThereIsNoSessionUntilObserved(): Unit = runBlocking {
        val id = pairedId()
        assertIs<PatchbayError.Unreachable>(adapter.command(id, Command.PressKey(RemoteKey.UP)).errorOrNull())
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.Media(MediaAction.PLAY)).errorOrNull())
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.SetVolume(5)).errorOrNull())
    }

    @Test
    fun aRestartedAdapterReusesTheStoredIdentity(): Unit = runBlocking {
        val d = found()
        val p = adapter.pair(d, Prompt(tv)).getOrNull()!!
        val fresh = adapter()
        assertTrue(fresh.restore(p.deviceId, d.address, p) is PatchbayResult.Ok)
        val snap = withTimeout(8_000) { fresh.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
        assertEquals(Reachability.ONLINE, snap.reachability)
    }

    @Test
    fun aTvThatForgotUsDropsTheSession(): Unit = runBlocking {
        val id = pairedId()
        tv.forgetClients()
        val snap = withTimeout(8_000) { adapter.observe(id).first() }
        assertEquals(Reachability.DEGRADED, snap.reachability)
    }

    @Test
    fun animpostorWithADifferentKeyIsTrustChanged(): Unit = runBlocking {
        val d = found()
        val p = adapter.pair(d, Prompt(tv)).getOrNull()!!
        val ports = tv.pairingPort to tv.remotePort
        tv.close()
        val impostor = AndroidTvSimulator("127.0.0.95", keystore("tv-b.p12"), "testpass".toCharArray(), ports.first, ports.second)
        try {
            val a2 = AndroidTvRemoteAdapter(NetGuard(), CastAdapter.CastFinder { listOf(finding) }, AndroidTvRemoteAdapter.Config(pairingPort = ports.first, remotePort = ports.second, connectTimeoutMs = 3_000, silentMs = 600, maxBackoffMs = 300))
            a2.restore(p.deviceId, "127.0.0.95", p)
            val snap = withTimeout(8_000) { a2.observe(p.deviceId).first() }
            assertEquals(Reachability.DEGRADED, snap.reachability)
            assertTrue(a2.trustChanged(p.deviceId))
            assertEquals(HealthState.TRUST_CHANGED, a2.health(p.deviceId).state)
        } finally { impostor.close() }
    }

    @Test
    fun aBadFrameLengthEndsTheSessionWithoutCrashing(): Unit = runBlocking {
        val id = pairedId()
        tv.badLength = true
        val snap = withTimeout(8_000) { adapter.observe(id).first() }
        assertEquals(Reachability.DEGRADED, snap.reachability)
        assertTrue(adapter.rejectedMessages >= 1)
    }
}
