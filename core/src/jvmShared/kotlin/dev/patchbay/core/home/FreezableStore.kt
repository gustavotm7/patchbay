package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.RoomId
import dev.patchbay.core.store.HomeStore
import dev.patchbay.core.store.StoredDevice
import dev.patchbay.core.store.StoredPairing

/**
 * What the running session writes through. After a backup is restored the session still holds the old state in memory, and its next change (a favourite, a scan, a
 * scene) would save that old state over the restored data. Once [freeze] is called every write is dropped (and counted) so the restored data stays exactly as restored
 * until Patchbay is really restarted. Reads still work.
 */
internal class FreezableStore(private val inner: HomeStore, private val onDropped: () -> Unit = {}) : HomeStore {
    @Volatile var frozen: Boolean = false
        private set

    fun freeze() { frozen = true }

    private inline fun write(block: () -> Unit) { if (frozen) onDropped() else block() }

    override fun devices(): List<StoredDevice> = inner.devices()
    override fun saveDevice(d: StoredDevice, now: Long) = write { inner.saveDevice(d, now) }
    override fun forget(id: DeviceId) = write { inner.forget(id) }
    override fun setRoom(id: DeviceId, room: RoomId?) = write { inner.setRoom(id, room) }
    override fun pairing(id: DeviceId): StoredPairing? = inner.pairing(id)
    override fun savePairing(p: StoredPairing) = write { inner.savePairing(p) }
    override fun setting(key: String): String? = inner.setting(key)
    override fun putSetting(key: String, value: String) = write { inner.putSetting(key, value) }
}
