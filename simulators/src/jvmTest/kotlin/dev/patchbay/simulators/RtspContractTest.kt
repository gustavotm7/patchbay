package dev.patchbay.simulators

import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.rtsp.RtspCameraAdapter
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test: the real RtspCameraAdapter against a simulated eufy-style RTSP camera over loopback TCP. */
class RtspContractTest {

    private val cam = RtspSimulator("127.0.0.30", "patch", "s3cret!pw")
    private val adapter = RtspCameraAdapter(
        NetGuard(),
        RtspCameraAdapter.Config(port = cam.port, connectTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400),
    )

    private class Prompt(val user: String?, val password: String?) : PairingPrompt {
        val labels = mutableListOf<String>()
        override suspend fun askForCode(label: String, length: IntRange): String? {
            labels += label
            return if (labels.size == 1) user else password
        }
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    @AfterTest
    fun tearDown() = cam.close()

    private fun found(name: String = "Front door"): DiscoveredDevice = adapter.manualDevice("127.0.0.30", name).getOrNull()!!

    @Test
    fun manualAddBuildsACameraEntry(): Unit = runBlocking {
        val d = found("  Front   door ")
        assertEquals(DeviceKind.CAMERA, d.kind)
        assertEquals("Front door", d.displayName)
        assertEquals("127.0.0.30", d.address)
        assertEquals("rtsp-127-0-0-30", adapter.deviceId(d).value)
        assertTrue(adapter.capabilities(adapter.deviceId(d)).single() is Capability.CameraStream)
        assertEquals(0, adapter.discover().toList().size) // RTSP can't be discovered
    }

    @Test
    fun manualAddRejectsBadNamesAndNonLanAddresses() {
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.30", "").errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.30", "a\u0000b").errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.manualDevice("127.0.0.30", "x".repeat(40)).errorOrNull())
        assertIs<PatchbayError.NetGuardDenied>(adapter.manualDevice("1.1.1.1", "Cam").errorOrNull())
        assertTrue(adapter.manualDevice("camera.local", "Cam") is PatchbayResult.Err) // no DNS
    }

    @Test
    fun pairsWithDigestAuthAndStoresCredentialsAsTheSecret(): Unit = runBlocking {
        val prompt = Prompt("patch", "s3cret!pw")
        val p = adapter.pair(found(), prompt).getOrNull()!!
        assertEquals("patch\ns3cret!pw", p.secret.decodeToString())
        assertEquals(null, p.pinnedFingerprint)
        assertEquals(2, prompt.labels.size)
        assertFalse(p.toString().contains("s3cret"), "Pairing.toString must redact the secret")
    }

    @Test
    fun pairsWithBasicAuth(): Unit = runBlocking {
        cam.mode = RtspSimulator.Mode.BASIC
        assertTrue(adapter.pair(found(), Prompt("patch", "s3cret!pw")) is PatchbayResult.Ok)
    }

    @Test
    fun credentialsAreSentOnlyAfterAChallenge(): Unit = runBlocking {
        adapter.pair(found(), Prompt("patch", "s3cret!pw"))
        assertEquals(2, cam.requests.size)
        assertFalse(cam.requests[0].contains("Authorization"))
        assertTrue(cam.requests[1].contains("Authorization: Digest"))
        assertFalse(cam.requests.any { it.contains("s3cret") }, "the password itself never goes on the wire in Digest")
    }

    @Test
    fun wrongPasswordIsUnauthorized(): Unit = runBlocking {
        assertIs<PatchbayError.Unauthorized>(adapter.pair(found(), Prompt("patch", "wrong")).errorOrNull())
    }

    @Test
    fun cancelledOrUnsafeCredentialsStopBeforeAnyNetworkTraffic(): Unit = runBlocking {
        assertIs<PatchbayError.InvalidArgument>(adapter.pair(found(), Prompt(null, null)).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(adapter.pair(found(), Prompt("pa\"tch", "x")).errorOrNull())
        assertEquals(0, cam.requests.size)
    }

    @Test
    fun openCameraWithoutAuthPairsToo(): Unit = runBlocking {
        cam.mode = RtspSimulator.Mode.NONE
        assertTrue(adapter.pair(found(), Prompt("a", "b")) is PatchbayResult.Ok)
    }

    @Test
    fun missingStreamPathIsReportedClearly(): Unit = runBlocking {
        val other = RtspCameraAdapter(NetGuard(), RtspCameraAdapter.Config(port = cam.port, path = "stream1", connectTimeoutMs = 800))
        val r = other.pair(other.manualDevice("127.0.0.30", "Cam").getOrNull()!!, Prompt("patch", "s3cret!pw"))
        assertTrue(r.errorOrNull()?.message?.contains("switched on") == true, r.toString())
    }

    @Test
    fun restoreThenObserveReportsOnlineThenBacksOff(): Unit = runBlocking {
        val p = adapter.pair(found(), Prompt("patch", "s3cret!pw")).getOrNull()!!
        val fresh = RtspCameraAdapter(NetGuard(), RtspCameraAdapter.Config(port = cam.port, connectTimeoutMs = 400, pollIntervalMs = 100, maxBackoffMs = 400))
        assertTrue(fresh.restore(p.deviceId, "127.0.0.30", p) is PatchbayResult.Ok) // as after an app restart
        val snaps = withTimeout(10_000) {
            val first = fresh.observe(p.deviceId).take(1).toList().single()
            cam.offline = true
            first to fresh.observe(p.deviceId).take(3).toList()
        }
        assertEquals(Reachability.ONLINE, snaps.first.reachability)
        assertEquals(listOf(Reachability.DEGRADED, Reachability.DEGRADED, Reachability.OFFLINE), snaps.second.map { it.reachability })
    }

    @Test
    fun garbageAndOversizeResponsesAreRejectedNotCrashing(): Unit = runBlocking {
        cam.garbage = true
        assertTrue(adapter.pair(found(), Prompt("patch", "s3cret!pw")) is PatchbayResult.Err)
        cam.garbage = false
        cam.hugeHeaders = true
        assertIs<PatchbayError.ProtocolViolation>(adapter.pair(found(), Prompt("patch", "s3cret!pw")).errorOrNull())
        assertTrue(adapter.rejectedMessages >= 2)
        cam.hugeHeaders = false
        assertTrue(adapter.pair(found(), Prompt("patch", "s3cret!pw")) is PatchbayResult.Ok) // still fine
    }

    @Test
    fun streamLeaseCarriesTheLoginInTheUrlButNeverInTheText(): Unit = runBlocking {
        val p = adapter.pair(found(), Prompt("patch", "s3cret!pw")).getOrNull()!!
        val lease = adapter.streamLease(p.deviceId).getOrNull()!!
        val uri = java.net.URI(lease.url)
        assertEquals("rtsp", uri.scheme)
        assertEquals("127.0.0.30", uri.host)
        assertEquals(cam.port, uri.port)
        assertEquals("/live0", uri.path)
        assertEquals("patch:s3cret!pw", uri.userInfo) // decoded: '!' survived percent-encoding round trip
        assertFalse(lease.toString().contains("s3cret"))
        assertFalse(lease.toString().contains("patch:"))
    }

    @Test
    fun specialCharactersInPasswordsAreEncodedInTheUrl(): Unit = runBlocking {
        val tricky = "p@ss:w/rd?#%+"
        val cam2 = RtspSimulator("127.0.0.31", "u", tricky)
        try {
            val a = RtspCameraAdapter(NetGuard(), RtspCameraAdapter.Config(port = cam2.port, connectTimeoutMs = 800))
            val d = a.manualDevice("127.0.0.31", "Tricky").getOrNull()!!
            val p = a.pair(d, Prompt("u", tricky)).getOrNull()!!
            val uri = java.net.URI(a.streamLease(p.deviceId).getOrNull()!!.url)
            assertEquals("u:$tricky", uri.userInfo)
            assertEquals("127.0.0.31", uri.host)
        } finally {
            cam2.close()
        }
    }

    @Test
    fun noStreamBeforeTheLoginIsKnown(): Unit = runBlocking {
        val d = found()
        adapter.restore(adapter.deviceId(d), "127.0.0.30", null)
        assertIs<PatchbayError.Unauthorized>(adapter.streamLease(adapter.deviceId(d)).errorOrNull())
        assertIs<PatchbayError.Unreachable>(adapter.streamLease(dev.patchbay.core.model.DeviceId("rtsp-unknown")).errorOrNull())
    }

    @Test
    fun camerasAreViewOnly(): Unit = runBlocking {
        val p = adapter.pair(found(), Prompt("patch", "s3cret!pw")).getOrNull()!!
        assertIs<PatchbayError.Unsupported>(adapter.command(p.deviceId, Command.SetPower(true)).errorOrNull())
    }
}
