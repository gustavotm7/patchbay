package dev.patchbay.simulators

import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.androidtv.AndroidTvRemoteAdapter
import dev.patchbay.core.adapter.bambu.BambuAdapter
import dev.patchbay.core.adapter.cast.CastAdapter
import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.adapter.homekit.HomeKitAdapter
import dev.patchbay.core.adapter.kasa.KasaAdapter
import dev.patchbay.core.adapter.rtsp.RtspCameraAdapter
import dev.patchbay.core.adapter.tuya.TuyaLocalAdapter
import dev.patchbay.core.adapter.webos.WebOsAdapter
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.discovery.HapAdvert
import dev.patchbay.core.log.Redactor
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What each adapter says about itself for the diagnostics report must be facts about shape and behaviour, never the secrets it holds. Each test
 * pairs a real adapter with a simulator using a known secret, makes it work and fail a little, and then looks for every plausible spelling of
 * that secret (and of the stored pairing bytes) in the adapter's report.
 */
class DiagnosticsSecretsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private class Prompt(val code: String?, val trust: Boolean = true, val codes: ((String?) -> String?)? = null) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = codes?.invoke(code) ?: code
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = trust
        override suspend fun instruct(message: String) {}
    }

    /** Every way a secret could end up in text by accident. */
    private fun spellings(secret: ByteArray): List<String> {
        val plain = secret.decodeToString()
        val hex = secret.joinToString("") { "%02x".format(it) }
        val b64 = Base64.getEncoder().encodeToString(secret)
        val url = Base64.getUrlEncoder().encodeToString(secret)
        return listOf(plain, plain.lowercase(), plain.uppercase(), hex, hex.uppercase(), b64, b64.trimEnd('='), url, url.trimEnd('=')).filter { it.length >= 4 }.distinct()
    }

    private fun report(adapter: DeviceAdapter, device: DeviceId): List<String> = adapter.diagnostics(device) + adapter.adapterDiagnostics()

    private fun assertSafe(lines: List<String>, vararg secrets: ByteArray) {
        val text = lines.joinToString("\n")
        for (secret in secrets) for (s in spellings(secret)) assertFalse(s in text, "a secret appears in the report as '${s.take(6)}…':\n$text")
        for (l in lines) {
            assertTrue(l.length <= 400, "line too long: ${l.take(80)}")
            assertFalse(l.any { it.isISOControl() }, "control characters in: $l")
        }
        // The redactor would leave the report as it is: nothing in it looks like a key, token, serial, MAC or identifier.
        for (l in lines) assertEquals(l, Redactor.redact(l), "the redactor would change this line, so it holds something that looks secret")
    }

    @Test
    fun tuyaReportsDataPointTypesButNotTheLocalKeyOrValues(): Unit = runBlocking {
        val devId = "bf1234567890abcdef12"; val key = "0123456789abcdef"
        val bulb = TuyaSimulator("127.0.0.150", devId, key)
        val adapter = TuyaLocalAdapter(NetGuard(), TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        try {
            fun found() = adapter.manualDevice("127.0.0.150", devId, "Bulb (standard)").getOrNull()!!
            assertTrue(adapter.pair(found(), Prompt("fedcba9876543210")) is PatchbayResult.Err) // wrong key: a failure on the record
            val p = adapter.pair(found(), Prompt(key)).getOrNull()!!
            adapter.restore(p.deviceId, "127.0.0.150", p)
            withTimeout(5_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            val lines = report(adapter, p.deviceId)
            assertTrue(lines.any { "local key stored: true" in it }, lines.toString())
            assertTrue(lines.any { it.startsWith("data points in the last reading (type only): ") && "20=bool" in it }, lines.toString())
            // How a device refuses a wrong key varies (an error code, garbage, or a closed connection); that it is on the record does not.
            assertTrue(lines.any { it.startsWith("problem: ") }, "the wrong key should be on the record: $lines")
            assertSafe(lines, key.encodeToByteArray(), "fedcba9876543210".encodeToByteArray(), p.secret, devId.encodeToByteArray())
        } finally { bulb.close() }
    }

    @Test
    fun bambuReportsFieldNamesButNotTheAccessCodeOrSerial(): Unit = runBlocking {
        val printer = BambuSimulator("127.0.0.151", "0948AD4A0B00001", "12345678", keystore("device-a.p12"), "testpass".toCharArray())
        val adapter = BambuAdapter(
            NetGuard(),
            BambuAdapter.Config(
                ssdpTargets = listOf("127.0.0.151"), ssdpPorts = listOf(printer.ssdpPort), mqttPort = printer.mqttPort,
                scanWindowMs = 500, connectTimeoutMs = 2_000, idleMs = 1_000, minEmitGapMs = 0, bindLoopbackOnly = true, notifyPort = 0,
            ),
        )
        try {
            val d = adapter.discover().toList().single()
            assertTrue(adapter.pair(d, Prompt("87654321")) is PatchbayResult.Err) // wrong code
            val p = adapter.pair(d, Prompt("12345678")).getOrNull()!!
            adapter.restore(p.deviceId, d.address, p)
            withTimeout(8_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            val lines = report(adapter, p.deviceId)
            assertTrue(lines.any { "access code stored: true" in it }, lines.toString())
            assertTrue(lines.any { it.startsWith("fields in the printer's report (names only): ") }, lines.toString())
            assertSafe(lines, "12345678".encodeToByteArray(), "87654321".encodeToByteArray(), p.secret, "0948AD4A0B00001".encodeToByteArray())
        } finally { adapter.close(); printer.close() }
    }

    @Test
    fun rtspReportsTheLoginMethodButNotTheLogin(): Unit = runBlocking {
        val cam = RtspSimulator("127.0.0.152", "patch", "s3cret!pw")
        val adapter = RtspCameraAdapter(NetGuard(), RtspCameraAdapter.Config(port = cam.port, connectTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        try {
            var n = 0
            val prompt = object : PairingPrompt {
                override suspend fun askForCode(label: String, length: IntRange): String? = if (n++ % 2 == 0) "patch" else "s3cret!pw"
                override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
                override suspend fun instruct(message: String) {}
            }
            val d = adapter.manualDevice("127.0.0.152", "Front door").getOrNull()!!
            val p = adapter.pair(d, prompt).getOrNull()!!
            adapter.restore(p.deviceId, "127.0.0.152", p)
            withTimeout(5_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            val lines = report(adapter, p.deviceId)
            assertTrue(lines.any { "login stored: true" in it }, lines.toString())
            assertTrue(lines.any { it.startsWith("last check of the stream: RTSP 401 (Digest), then 200") }, lines.toString())
            assertSafe(lines, "s3cret!pw".encodeToByteArray(), p.secret)
            assertFalse(lines.any { "patch\n" in it || "patch:" in it })
        } finally { cam.close() }
    }

    @Test
    fun webOsReportsWhichRequestsWorkedButNotTheClientKey(): Unit = runBlocking {
        val tv = WebOsSimulator("127.0.0.153", keystore("device-a.p12"), "testpass".toCharArray())
        val adapter = WebOsAdapter(
            NetGuard(),
            WebOsAdapter.Config(
                ssdpTargets = listOf("127.0.0.153"), ssdpPort = tv.ssdpPort, wssPort = tv.wssPort, scanWindowMs = 500,
                replyTimeoutMs = 2_000, pairingTimeoutMs = 3_000, pollIntervalMs = 100, maxBackoffMs = 300, bindLoopbackOnly = true,
                wolTargets = listOf("127.0.0.153"), wolPort = tv.wolPort,
            ),
        )
        try {
            val d = adapter.discover().toList().single()
            val p = adapter.pair(d, Prompt(null)).getOrNull()!!
            adapter.restore(p.deviceId, d.address, p)
            withTimeout(8_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            val lines = report(adapter, p.deviceId)
            assertTrue(lines.any { "client key stored: true" in it }, lines.toString())
            assertTrue(lines.any { it == "request audio getVolume: ok" }, lines.toString())
            val clientKey = p.secret.decodeToString().substringBefore('\n')
            assertSafe(lines, clientKey.encodeToByteArray(), p.secret)
        } finally { adapter.close(); tv.close() }
    }

    @Test
    fun googleTvRemoteReportsMessageKindsButNotTheClientIdentityOrCode(): Unit = runBlocking {
        val tv = AndroidTvSimulator("127.0.0.154", keystore("tv-a.p12"), "testpass".toCharArray())
        val finding = Finding("127.0.0.154", emptySet(), setOf("_androidtvremote2._tcp"), setOf(tv.remotePort), "Living room TV", "Chromecast", null)
        val adapter = AndroidTvRemoteAdapter(
            NetGuard(), CastAdapter.CastFinder { listOf(finding) },
            AndroidTvRemoteAdapter.Config(pairingPort = tv.pairingPort, remotePort = tv.remotePort, connectTimeoutMs = 3_000, silentMs = 600, maxBackoffMs = 300),
        )
        try {
            val d = adapter.discover().toList().single()
            var shown: String? = null
            val prompt = object : PairingPrompt {
                override suspend fun askForCode(label: String, length: IntRange): String? = (tv.displayedCode ?: "000000").also { shown = it }
                override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
                override suspend fun instruct(message: String) {}
            }
            val p = adapter.pair(d, prompt).getOrNull()!!
            adapter.restore(p.deviceId, d.address, p)
            withTimeout(8_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            val lines = report(adapter, p.deviceId)
            assertTrue(lines.any { "client identity present: true" in it }, lines.toString())
            assertTrue(lines.any { it.startsWith("remote message kinds seen: ") && "started" in it }, lines.toString())
            assertSafe(lines, p.secret, requireNotNull(shown).encodeToByteArray())
        } finally { tv.close() }
    }

    @Test
    fun homeKitReportsTheShapeAndPairingMethodButNotTheSetupCodeOrKeys(): Unit = runBlocking {
        val sim = HomeKitSimulator("127.0.0.155")
        val finding = Finding(sim.address, emptySet(), setOf("_hap._tcp"), setOf(sim.port), "Hallway", "ecobee Premium", null, HapAdvert(sim.port, sim.accessoryId, 9, true))
        val adapter = HomeKitAdapter(NetGuard(), CastAdapter.CastFinder { listOf(finding) }, HomeKitAdapter.Config(connectTimeoutMs = 2_000, ioTimeoutMs = 3_000, pollIntervalMs = 100, maxBackoffMs = 300))
        try {
            val d = adapter.discover().toList().single()
            val p = adapter.pair(d, Prompt("314-15-926")).getOrNull()!!
            adapter.restore(p.deviceId, d.address, p)
            withTimeout(10_000) { adapter.observe(p.deviceId).first { it.reachability == Reachability.ONLINE } }
            val lines = report(adapter, p.deviceId)
            assertTrue(lines.any { it.startsWith("pair-setup method used in this session: 0") }, lines.toString())
            assertTrue(lines.any { it.startsWith("accessories and services: aid 1: ") && "0x4A" in it }, lines.toString())
            assertTrue(lines.any { it.startsWith("thermostat layout: current-temp(") }, lines.toString())
            assertSafe(lines, p.secret, "314-15-926".encodeToByteArray(), "31415926".encodeToByteArray(), sim.accessoryId.encodeToByteArray())
        } finally { adapter.close(); sim.close() }
    }

    @Test
    fun goveeKasaAndCastDescribeThemselvesWithoutIdentifiers(): Unit = runBlocking {
        // Govee
        val lamp = GoveeSimulator("127.0.0.156", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val govee = GoveeLanAdapter(
            NetGuard(), scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.156"), listenPort = 0, bindLoopbackOnly = true, scanPort = lamp.scanPort, commandPort = lamp.commandPort,
                scanWindowMs = 600, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        lamp.replyPort = (govee.start() as PatchbayResult.Ok).value
        // Kasa
        val kasaKey = "80060000000000000000000000000000000000aa"
        val plug = KasaSimulator("127.0.0.157", kasaKey)
        val kasa = KasaAdapter(NetGuard(), scope, KasaAdapter.Config(scanTargets = listOf("127.0.0.157"), port = plug.port, includeLocalBroadcasts = false, scanWindowMs = 600, ioTimeoutMs = 500, pollIntervalMs = 100, maxBackoffMs = 400))
        // Cast
        val tv = CastSimulator("127.0.0.158", keystore("device-a.p12"), "testpass".toCharArray())
        val castFinding = Finding("127.0.0.158", emptySet(), setOf("_googlecast._tcp"), setOf(tv.port), "Living room TV", "Chromecast", null, null, mapOf("_googlecast._tcp" to "9f1c2d3e4b5a69788796a5b4c3d2e1f0"))
        val cast = CastAdapter(NetGuard(), { listOf(castFinding) }, CastAdapter.Config(port = tv.port, connectTimeoutMs = 2_000, heartbeatMs = 300, minEmitGapMs = 0, maxBackoffMs = 300))
        try {
            val g = govee.discover().toList().single()
            val gl = report(govee, govee.deviceId(g))
            assertTrue(gl.any { it.startsWith("model H6076; Wi-Fi firmware ") }, gl.toString())
            assertSafe(gl, "1F:80:C5:32:32:36:72:4E".encodeToByteArray(), "1F80C5323236724E".encodeToByteArray())

            val k = kasa.discover().toList().single()
            val kl = report(kasa, kasa.deviceId(k))
            assertTrue(kl.any { it.startsWith("model ") && "firmware" in it }, kl.toString())
            assertSafe(kl, kasaKey.encodeToByteArray())

            val c = cast.discover().toList().single()
            val p = cast.pair(c, Prompt(null)).getOrNull()!!
            cast.restore(p.deviceId, c.address, p)
            val job = scope.launch { cast.observe(p.deviceId).collect {} }
            withTimeout(10_000) { while (!report(cast, p.deviceId).any { it.startsWith("message namespaces seen: ") && "receiver" in it }) { tv.pushStatus(); delay(100) } }
            job.cancel()
            val cl = report(cast, p.deviceId)
            assertSafe(cl, "9f1c2d3e4b5a69788796a5b4c3d2e1f0".encodeToByteArray())
        } finally { govee.close(); lamp.close(); plug.close(); tv.close(); scope.cancel() }
    }
}
