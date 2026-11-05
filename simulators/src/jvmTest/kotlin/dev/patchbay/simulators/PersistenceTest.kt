package dev.patchbay.simulators

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.store.DesktopStorage
import dev.patchbay.core.store.SqlHomeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** A device found once is shown and polled after a restart without scanning again. */
class PersistenceTest {
    @Test
    fun restoredDeviceIsPolledWithoutScan(): Unit = runBlocking {
        val db = File(Files.createTempDirectory("pbpersist").toFile(), "p.db")
        val key = ByteArray(32) { 3 }
        val lamp = GoveeSimulator("127.0.0.5", "1F:80:C5:32:32:36:72:4E", "H6076", replyPort = 0)
        fun config() = GoveeLanAdapter.Config(
            scanTargets = listOf("127.0.0.5"), listenPort = 0, bindLoopbackOnly = true,
            scanPort = lamp.scanPort, commandPort = lamp.commandPort, scanWindowMs = 400, statusTimeoutMs = 300, pollIntervalMs = 100,
        )

        // Session 1: scan and save.
        run {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val guard = NetGuard()
            val adapter = GoveeLanAdapter(guard, scope, config())
            lamp.replyPort = (adapter.start() as PatchbayResult.Ok).value
            DesktopStorage.open(db, key).use { driver ->
                val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)))
                home.scan()
                assertEquals(1, home.devices.value.size)
            }
            adapter.close(); scope.cancel()
        }

        // Session 2: no scan.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = NetGuard()
        val adapter = GoveeLanAdapter(guard, scope, config())
        lamp.replyPort = (adapter.start() as PatchbayResult.Ok).value
        DesktopStorage.open(db, key).use { driver ->
            val home = LanHome(guard, listOf(adapter), scope, null, SqlHomeStore(PatchbayDb(driver)))
            assertEquals("Govee H6076", home.devices.value.single().device.name)
            delay(500)
            assertEquals(Reachability.ONLINE, home.devices.value.single().snapshot?.reachability)
        }
        adapter.close(); lamp.close(); scope.cancel()
    }
}
