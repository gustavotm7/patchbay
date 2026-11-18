package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.tuya.TuyaLocalAdapter
import dev.patchbay.core.home.LanHome
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The point of a backup: pair once, lose the phone, restore on a new one, and the device is back without pairing again. */
class BackupRoundTripTest {
    private val devId = "bf1234567890abcdef12"
    private val key = "0123456789abcdef"

    private class KeyPrompt(val key: String) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = key
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun aRestoredBackupBringsAPairedDeviceBackWithoutPairingAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bulb = TuyaSimulator("127.0.0.163", devId, key)
        val guard = NetGuard()
        fun adapter() = TuyaLocalAdapter(guard, TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        try {
            // The old phone: add, pair, name it, make a backup.
            val oldStore = InMemoryHomeStore()
            val oldHome = LanHome(guard, listOf(adapter()), scope, null, oldStore)
            val id = (oldHome.addManually(TuyaLocalAdapter.ID, "127.0.0.163", devId, "Bulb (standard)") as PatchbayResult.Ok).value
            assertTrue(oldHome.pair(id, KeyPrompt(key)) is PatchbayResult.Ok)
            assertTrue(oldHome.rename(id, "Bedside lamp") is PatchbayResult.Ok)
            assertTrue(oldHome.assignRoom(id, "Bedroom") == Unit)
            assertTrue(oldHome.createBackup("short") is PatchbayResult.Err)
            val text = (oldHome.createBackup("a long enough passphrase") as PatchbayResult.Ok).value
            assertFalse(key in text)

            // The new phone: nothing saved, a wrong passphrase changes nothing, the right one brings it all back.
            val newStore = InMemoryHomeStore()
            val newHome = LanHome(guard, listOf(adapter()), scope, null, newStore)
            assertTrue(newHome.restoreBackup(text, "not the passphrase!") is PatchbayResult.Err)
            assertEquals(emptyList(), newStore.devices())
            val message = (newHome.restoreBackup(text, "a long enough passphrase") as PatchbayResult.Ok).value
            assertTrue("Quit Patchbay" in message)
            assertTrue(newHome.problem.value!!.contains("Quit Patchbay"))

            // Until the real restart, the running session must not write its old state over what was just restored.
            val before = newStore.setting("favourites")
            newHome.setFavourite(id, true)
            newHome.rename(id, "Changed after restore")
            newHome.assignRoom(id, "Garage")
            assertEquals(before, newStore.setting("favourites"))
            assertEquals("Bedside lamp", newStore.devices().single().name)
            assertEquals("Bedroom", newStore.devices().single().roomId?.value)

            // "Restart": a home built from what was restored.
            val restarted = LanHome(guard, listOf(adapter()), scope, null, newStore)
            val d = restarted.devices.value.single()
            assertFalse(d.needsPairing)
            assertEquals("Bedside lamp", d.device.name)
            assertEquals("Bedroom", d.device.roomId?.value)
            withTimeout(8_000) { while (restarted.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }
        } finally { bulb.close(); scope.cancel() }
    }
}
