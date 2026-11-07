package dev.patchbay.core.store

import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.RoomId

/**
 * Wraps the data-encryption key (DEK) with an OS-protected key (privacy invariant 4):
 * Android Keystore (StrongBox when available), Windows DPAPI. Implementations live in the platform shells
 * or in jvmMain.
 */
public interface KeyWrapper {
    /** Short name for logs and the settings screen, e.g. "android-keystore-strongbox", "windows-dpapi". */
    public val kind: String

    /** True when the wrapping key is hardware- or OS-protected; false for the hub's file fallback. */
    public val osProtected: Boolean

    public fun wrap(plain: ByteArray): ByteArray

    public fun unwrap(wrapped: ByteArray): ByteArray
}

public data class StoredDevice(
    val id: DeviceId,
    val adapter: String,
    val adapterKey: String,
    val name: String,
    val kind: DeviceKind,
    val vendor: String?,
    val model: String?,
    val address: String,
    val port: Int?,
    val controlPath: ControlPath,
    val roomId: RoomId?,
    val firstSeen: Long,
    val lastSeen: Long,
)

public class StoredPairing(public val deviceId: DeviceId, public val secret: ByteArray, public val pinnedSha256: String?, public val pairedAt: Long) {
    override fun toString(): String = "StoredPairing(deviceId=$deviceId, secret=<redacted>, pinnedSha256=$pinnedSha256)"
}

/** The app's persistent state. Backed by the encrypted SQLDelight database. */
public interface HomeStore {
    public fun devices(): List<StoredDevice>
    public fun saveDevice(d: StoredDevice, now: Long)
    public fun forget(id: DeviceId)

    /** Puts a saved device in a room, or takes it out with null. (saveDevice deliberately keeps the stored room.) */
    public fun setRoom(id: DeviceId, room: RoomId?)
    public fun pairing(id: DeviceId): StoredPairing?
    public fun savePairing(p: StoredPairing)
    public fun setting(key: String): String?
    public fun putSetting(key: String, value: String)
}

public class SqlHomeStore(private val db: PatchbayDb) : HomeStore {
    private val q get() = db.patchbayQueries

    override fun devices(): List<StoredDevice> = q.allDevices().executeAsList().mapNotNull { r ->
        val kind = DeviceKind.entries.firstOrNull { it.name == r.kind } ?: return@mapNotNull null
        val path = ControlPath.entries.firstOrNull { it.name == r.control_path } ?: return@mapNotNull null
        StoredDevice(
            DeviceId(r.id), r.adapter, r.adapter_key, r.name, kind, r.vendor, r.model, r.address, r.port?.toInt(), path,
            r.room_id?.let(::RoomId), r.first_seen, r.last_seen,
        )
    }

    override fun saveDevice(d: StoredDevice, now: Long) {
        q.upsertDevice(
            id = d.id.value, adapter = d.adapter, adapter_key = d.adapterKey, name = d.name, kind = d.kind.name,
            vendor = d.vendor, model = d.model, address = d.address, port = d.port?.toLong(), control_path = d.controlPath.name,
            now = now,
        )
    }

    override fun setRoom(id: DeviceId, room: RoomId?) {
        q.setDeviceRoom(room?.value, id.value)
    }

    override fun forget(id: DeviceId) {
        db.transaction {
            q.forgetDevice(id.value)
            q.deletePairing(id.value)
        }
    }

    override fun pairing(id: DeviceId): StoredPairing? =
        q.pairingFor(id.value).executeAsOneOrNull()?.let { StoredPairing(DeviceId(it.device_id), it.secret, it.pinned_sha256, it.paired_at) }

    override fun savePairing(p: StoredPairing) {
        q.savePairing(p.deviceId.value, p.secret, p.pinnedSha256, p.pairedAt)
    }

    override fun setting(key: String): String? = q.setting(key).executeAsOneOrNull()

    override fun putSetting(key: String, value: String) {
        q.putSetting(key, value)
    }
}

/**
 * Used only when the encrypted database can't be opened. Nothing is written to disk (so invariant 4 still
 * holds), and the UI says that nothing will be saved this session.
 */
public class InMemoryHomeStore : HomeStore {
    private val devices = LinkedHashMap<DeviceId, StoredDevice>()
    private val pairings = LinkedHashMap<DeviceId, StoredPairing>()
    private val settings = LinkedHashMap<String, String>()

    override fun devices(): List<StoredDevice> = devices.values.sortedBy { it.name }
    override fun saveDevice(d: StoredDevice, now: Long) {
        val old = devices[d.id]
        devices[d.id] = d.copy(roomId = old?.roomId ?: d.roomId, firstSeen = old?.firstSeen ?: now, lastSeen = now)
    }
    override fun setRoom(id: DeviceId, room: RoomId?) {
        devices[id]?.let { devices[id] = it.copy(roomId = room) }
    }
    override fun forget(id: DeviceId) {
        devices.remove(id); pairings.remove(id)
    }
    override fun pairing(id: DeviceId): StoredPairing? = pairings[id]
    override fun savePairing(p: StoredPairing) { pairings[p.deviceId] = p }
    override fun setting(key: String): String? = settings[key]
    override fun putSetting(key: String, value: String) { settings[key] = value }
}
