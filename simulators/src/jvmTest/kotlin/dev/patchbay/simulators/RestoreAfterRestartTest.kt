package dev.patchbay.simulators

import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.tuya.TuyaLocalAdapter
import dev.patchbay.core.home.LanHome
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.store.InMemoryHomeStore
import dev.patchbay.core.store.StoredDevice
import dev.patchbay.core.store.StoredPairing
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

/**
 * Devices that need a pairing (a key, a code, a pinned certificate) come back after a restart too: paired ones are polled with what was saved, and
 * ones that were found but never paired are shown as needing pairing and can still be paired. Before this was tested, a saved device of either
 * kind made the home fail while it was being built.
 */
class RestoreAfterRestartTest {
    private val devId = "bf1234567890abcdef12"
    private val key = "0123456789abcdef"

    private fun saved(store: InMemoryHomeStore, address: String, port: Int, paired: Boolean) {
        val id = TuyaLocalAdapter.deviceIdFor(devId)
        store.saveDevice(
            StoredDevice(id, TuyaLocalAdapter.ID.value, devId, "Tuya bulb-v2", DeviceKind.LIGHT, "Tuya", "bulb-v2", address, port, ControlPath.LOCAL_AFTER_SETUP, null, 1, 1),
            now = 1,
        )
        if (paired) store.savePairing(StoredPairing(id, "bulb-v2\n$key".encodeToByteArray(), null, 1))
    }

    private class KeyPrompt(val key: String) : PairingPrompt {
        override suspend fun askForCode(label: String, length: IntRange): String? = key
        override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean = false
        override suspend fun instruct(message: String) {}
    }

    @Test
    fun aPairedDeviceIsPolledAgainAfterARestart(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bulb = TuyaSimulator("127.0.0.161", devId, key)
        val store = InMemoryHomeStore()
        saved(store, "127.0.0.161", bulb.port, paired = true)
        val guard = NetGuard()
        val adapter = TuyaLocalAdapter(guard, TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        try {
            val home = LanHome(guard, listOf(adapter), scope, null, store)
            val d = home.devices.value.single()
            assertFalse(d.needsPairing, "a paired device must not ask to be paired again")
            withTimeout(8_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }
            assertTrue(home.devices.value.single().snapshot?.state<CapabilityState.PowerState>() != null)
        } finally { bulb.close(); scope.cancel() }
    }

    @Test
    fun aDeviceFoundButNeverPairedStillNeedsPairingAndCanBePairedAfterARestart(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bulb = TuyaSimulator("127.0.0.162", devId, key)
        val store = InMemoryHomeStore()
        saved(store, "127.0.0.162", bulb.port, paired = false)
        val guard = NetGuard()
        val adapter = TuyaLocalAdapter(guard, TuyaLocalAdapter.Config(port = bulb.port, ioTimeoutMs = 800, pollIntervalMs = 100, maxBackoffMs = 400))
        try {
            val home = LanHome(guard, listOf(adapter), scope, null, store)
            val d = home.devices.value.single()
            assertTrue(d.needsPairing)
            assertEquals(DeviceId("tuya-$devId"), d.device.id)

            assertTrue(home.pair(d.device.id, KeyPrompt(key)) is PatchbayResult.Ok)
            withTimeout(8_000) { while (home.devices.value.single().snapshot?.reachability != Reachability.ONLINE) delay(50) }
            assertFalse(home.devices.value.single().needsPairing)
        } finally { bulb.close(); scope.cancel() }
    }
}
