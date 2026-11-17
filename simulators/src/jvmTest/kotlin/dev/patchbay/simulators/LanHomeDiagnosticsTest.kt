package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.adapter.tuya.TuyaLocalAdapter
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.log.SessionLog
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The report a person copies from Settings, built from a real `LanHome` with two real adapters and secrets that must never appear in it. */
class LanHomeDiagnosticsTest {
    private class KeyPrompt(val key: String) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = key
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun theReportHasFactsAndNoNamesSecretsOrFullAddresses(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dir = Files.createTempDirectory("pbdiag").toFile()
        val log = SessionLog.start(dir, "test")
        val guard = NetGuard()
        val devId = "bf1234567890abcdef12"; val key = "0123456789abcdef"
        val lamp = GoveeSimulator("127.0.0.159", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        val govee = GoveeLanAdapter(
            guard, scope,
            GoveeLanAdapter.Config(
                scanTargets = listOf("127.0.0.159"), listenPort = 0, bindLoopbackOnly = true, scanPort = lamp.scanPort, commandPort = lamp.commandPort,
                scanWindowMs = 500, statusTimeoutMs = 400, pollIntervalMs = 100, minCommandGapMs = 20,
            ),
        )
        lamp.replyPort = (govee.start() as PatchbayResult.Ok).value
        val bulb = TuyaSimulator("127.0.0.160", devId, key)
        val tuya = TuyaLocalAdapter(guard, TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        val home = LanHome(guard, listOf(govee, tuya), scope, log, InMemoryHomeStore())
        try {
            home.scan()
            val tuyaId = (home.addManually(TuyaLocalAdapter.ID, "127.0.0.160", devId, "Bulb (standard)") as PatchbayResult.Ok).value
            assertTrue(home.pair(tuyaId, KeyPrompt(key)) is PatchbayResult.Ok)
            assertTrue(home.rename(tuyaId, "Gustavo private bedroom lamp") is PatchbayResult.Ok)
            withTimeout(10_000) {
                while (home.devices.value.size != 2 || home.devices.value.any { it.snapshot?.reachability != Reachability.ONLINE || it.snapshot?.states.isNullOrEmpty() }) delay(50)
            }
            log.info("test", "pairing used access_code=12345678 and the key $key for Gustavo private bedroom lamp at 127.0.0.160")

            val text = home.diagnosticsReport(listOf("Patchbay test build"))

            for (part in listOf("Patchbay diagnostics", "Patchbay test build", "== Systems ==", "== Devices ==", "== Network ==", "== Recent activity (newest first) ==", "== Log tail (already redacted) ==")) {
                assertTrue(part in text, "missing: $part\n$text")
            }
            assertTrue("Govee lights (govee-lan): 1 added; found in the last scan: 1\n" in text, text)
            assertTrue("Tuya bulbs (tuya-local): 1 added\n" in text, text)
            assertTrue("Renamed" in text && "was " !in text.substringAfter("== Recent activity"), text)
            assertTrue("model H6076; Wi-Fi firmware " in text, text)
            assertTrue("data points in the last reading (type only): 20=bool" in text, text)
            assertTrue("local key stored: true" in text, text)
            assertTrue("address 127.0.*.159" in text && "address 127.0.*.160" in text, text)
            assertTrue("id tuya-…ef12" in text, text)
            assertTrue("kinds of reading: PowerState" in text, text)
            assertTrue("NetGuard: allowed " in text && "refused 0" in text, text)

            for (secret in listOf(key, devId, "12345678", "Gustavo", "127.0.0.160", "127.0.0.159", "1F:80:C5:32:32:36", "1F80C5323236724E", "bedroom")) {
                assertFalse(secret in text, "the report contains '$secret':\n$text")
            }
            assertTrue(text.length <= 30_100)
            assertEquals(text, text.filter { !it.isISOControl() || it == '\n' })
        } finally {
            govee.close(); lamp.close(); bulb.close(); log.close(); scope.cancel()
            dir.deleteRecursively()
        }
    }
}
