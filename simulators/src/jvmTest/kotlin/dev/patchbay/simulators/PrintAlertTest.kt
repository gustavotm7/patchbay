package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.bambu.BambuAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.store.DesktopStorage
import dev.patchbay.core.store.SqlHomeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

class PrintAlertTest {
    private fun keystore(name: String) = KeyStore.getInstance("PKCS12").apply {
        File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
    }

    private object Yes : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = "12345678"
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = true
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun aFinishedPrintNotifiesAndIsRecorded(): Unit = runBlocking {
        val printer = BambuSimulator("127.0.0.20", "0948AD4A0B00001", "12345678", keystore("device-a.p12"), "testpass".toCharArray())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = BambuAdapter(
            guard,
            BambuAdapter.Config(
                ssdpTargets = listOf("127.0.0.20"), ssdpPorts = listOf(printer.ssdpPort), mqttPort = printer.mqttPort,
                scanWindowMs = 500, connectTimeoutMs = 2_000, idleMs = 1_000, minEmitGapMs = 0, bindLoopbackOnly = true, notifyPort = 0,
            ),
        )
        val driver = DesktopStorage.open(File(Files.createTempDirectory("pbprint").toFile(), "p.db"), ByteArray(32) { 5 })
        val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)))
        try {
            home.scan()
            val id = home.devices.value.single().device.id
            assertTrue(home.pair(id, Yes) is PatchbayResult.Ok)
            val got = CopyOnWriteArrayList<String>()
            scope.launch { home.notifications.collect { got += it } }
            withTimeout(8_000) { while (home.devices.value.single().snapshot?.states.isNullOrEmpty()) delay(50) }
            delay(200)
            printer.gcodeState = "FINISH"
            printer.tick(100, 240)
            try { withTimeout(8_000) { while (got.none { "print finished" in it }) delay(50) } } catch (e: Exception) { throw AssertionError("no alert; got=$got activity=${home.activity.value} snap=${home.devices.value.single().snapshot?.states}", e) }
            withTimeout(8_000) { while (home.activity.value.none { "finished" in it.event }) delay(50) }
        } finally {
            adapter.close(); printer.close(); driver.close(); scope.cancel()
        }
    }
}
