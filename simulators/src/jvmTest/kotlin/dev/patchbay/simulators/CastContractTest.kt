package dev.patchbay.simulators

import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.MediaAction
import dev.patchbay.core.model.PlaybackStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test: the real CastAdapter against a simulated Chromecast over loopback TLS. */
class CastContractTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private val cast = CastSimulator("127.0.0.80", keystore("device-a.p12"), "testpass".toCharArray())
    private val finding = Finding("127.0.0.80", setOf("living-room-tv.local"), setOf("_googlecast._tcp"), setOf(cast.port), "Living room TV", "Chromecast with Google TV", null)
    private val adapter = CastAdapter(
        NetGuard(), { listOf(finding, Finding("127.0.0.81", emptySet(), setOf("_hap._tcp"), emptySet(), "Not a cast device", null, null)) },
        CastAdapter.Config(port = cast.port, connectTimeoutMs = 2_000, heartbeatMs = 300, minEmitGapMs = 0, maxBackoffMs = 300),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Prompt(val trust: Boolean = true) : PairingPrompt {
        var fingerprint: String? = null
        override suspend fun askForCode(label: String, length: IntRange): String? = null
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean { fingerprint = sha256; return trust }
        override suspend fun instruct(message: String) {}
    }

    @AfterTest
    fun tearDown() { scope.cancel(); cast.close() }

    private suspend fun found(): DiscoveredDevice = adapter.discover().toList().single()

    private suspend fun paired(): DeviceId {
        val d = found()
        val p = adapter.pair(d, Prompt()).getOrNull()!!
        adapter.restore(p.deviceId, d.address, p)
        return p.deviceId
    }

    private fun CopyOnWriteArrayList<dev.patchbay.core.model.DeviceSnapshot>.latest() = lastOrNull { it.reachability == Reachability.ONLINE }

    @Test
    fun discoversOnlyCastDevicesWithTheirNameAndModel(): Unit = runBlocking {
        val d = found()
        assertEquals("Living room TV", d.displayName)
        assertEquals("Chromecast with Google TV", d.model)
        assertEquals(DeviceKind.MEDIA_RECEIVER, d.kind)
        assertEquals("cast-ip-127-0-0-80", adapter.deviceId(d).value)
        val speaker = CastAdapter(NetGuard(), { listOf(Finding("127.0.0.82", emptySet(), setOf("_googlecast._tcp"), emptySet(), "Kitchen", "Google Nest Mini", null)) })
        assertEquals(DeviceKind.SPEAKER, speaker.discover().toList().single().kind)
    }

    @Test
    fun pairingIsTheFingerprintCheckAndCancelIsQuiet(): Unit = runBlocking {
        val prompt = Prompt()
        val p = adapter.pair(found(), prompt).getOrNull()!!
        assertEquals(0, p.secret.size)
        assertTrue(p.pinnedFingerprint?.length == 64)
        assertEquals(p.pinnedFingerprint, prompt.fingerprint?.replace(" ", ""))
        assertIs<PatchbayError.Cancelled>(adapter.pair(found(), Prompt(trust = false)).errorOrNull())
    }

    @Test
    fun observeReportsVolumeAppAndNowPlaying(): Unit = runBlocking {
        val id = paired()
        val snaps = CopyOnWriteArrayList<dev.patchbay.core.model.DeviceSnapshot>()
        scope.launch { adapter.observe(id).collect { snaps += it } }
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.MediaState>()?.title != "Song") delay(20) }
        val s = snaps.latest()!!
        assertEquals(CapabilityState.VolumeState(40, false), s.state<CapabilityState.VolumeState>())
        val media = s.state<CapabilityState.MediaState>()!!
        assertEquals(PlaybackStatus.PLAYING, media.status)
        assertEquals("Band", media.subtitle)
        assertEquals(12_500L, media.positionMs)
        assertEquals(240_000L, media.durationMs)
    }

    @Test
    fun volumeMuteAndTransportCommandsReachTheDevice(): Unit = runBlocking {
        val id = paired()
        val snaps = CopyOnWriteArrayList<dev.patchbay.core.model.DeviceSnapshot>()
        scope.launch { adapter.observe(id).collect { snaps += it } }
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.MediaState>()?.title != "Song") delay(20) }

        assertTrue(adapter.command(id, Command.SetVolume(70)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.VolumeState>()?.level != 70) delay(20) }
        assertEquals(0.7, cast.volume)
        assertTrue(adapter.command(id, Command.SetMute(true)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.VolumeState>()?.muted != true) delay(20) }

        assertTrue(adapter.command(id, Command.Media(MediaAction.PAUSE)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.MediaState>()?.status != PlaybackStatus.PAUSED) delay(20) }
        assertTrue(adapter.command(id, Command.Media(MediaAction.PLAY)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.MediaState>()?.status != PlaybackStatus.PLAYING) delay(20) }
        assertTrue(cast.received.any { it.second == "web-5" && "\"PAUSE\"" in it.third && "mediaSessionId" in it.third })
    }

    @Test
    fun unsupportedCommandsAndNothingPlayingAreRefused(): Unit = runBlocking {
        val id = paired()
        val snaps = CopyOnWriteArrayList<dev.patchbay.core.model.DeviceSnapshot>()
        scope.launch { adapter.observe(id).collect { snaps += it } }
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.MediaState>()?.title != "Song") delay(20) }
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.SetPower(true)).errorOrNull())
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.Media(MediaAction.NEXT)).errorOrNull())
        assertTrue(adapter.command(id, Command.Media(MediaAction.STOP)) is PatchbayResult.Ok)
        withTimeout(5_000) { while (cast.appRunning) delay(20) }
        withTimeout(5_000) { while (snaps.latest()?.state<CapabilityState.MediaState>()?.status != PlaybackStatus.IDLE) delay(20) }
        assertIs<PatchbayError.Unsupported>(adapter.command(id, Command.Media(MediaAction.PLAY)).errorOrNull()) // nothing to play now
    }

    @Test
    fun noCommandsBeforeASessionExists(): Unit = runBlocking {
        val d = found()
        val p = adapter.pair(d, Prompt()).getOrNull()!!
        adapter.restore(p.deviceId, d.address, p)
        assertIs<PatchbayError.Unreachable>(adapter.command(p.deviceId, Command.SetVolume(10)).errorOrNull())
    }

    @Test
    fun animpostorWithADifferentKeyIsTrustChanged(): Unit = runBlocking {
        val d = found()
        val p = adapter.pair(d, Prompt()).getOrNull()!!
        cast.close()
        val impostor = CastSimulator("127.0.0.80", keystore("device-b.p12"), "testpass".toCharArray())
        try {
            val a2 = CastAdapter(NetGuard(), { listOf(finding.copy(ports = setOf(impostor.port))) }, CastAdapter.Config(port = impostor.port, connectTimeoutMs = 2_000, heartbeatMs = 300, maxBackoffMs = 300))
            a2.restore(p.deviceId, "127.0.0.80", p)
            val snap = withTimeout(5_000) { a2.observe(p.deviceId).first() }
            assertEquals(Reachability.DEGRADED, snap.reachability)
            assertEquals(dev.patchbay.core.adapter.HealthState.TRUST_CHANGED, a2.health(p.deviceId).state)
        } finally {
            impostor.close()
        }
    }

    @Test
    fun observeBacksOffWhenTheDeviceGoesAway(): Unit = runBlocking {
        val id = paired()
        cast.offline = true
        val snaps = withTimeout(10_000) {
            val out = CopyOnWriteArrayList<dev.patchbay.core.model.DeviceSnapshot>()
            scope.launch { adapter.observe(id).collect { out += it } }
            while (out.size < 3) delay(50)
            out.toList()
        }
        assertFalse(snaps.any { it.reachability == Reachability.ONLINE })
        assertTrue(snaps.any { it.reachability == Reachability.DEGRADED })
    }

    @Test
    fun aBadFrameLengthEndsTheSessionWithoutCrashing(): Unit = runBlocking {
        val id = paired()
        cast.badLength = true
        val snap = withTimeout(5_000) { adapter.observe(id).first() }
        assertEquals(Reachability.DEGRADED, snap.reachability)
        assertTrue(adapter.rejectedMessages >= 1)
    }
}
