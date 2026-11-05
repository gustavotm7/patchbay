package dev.patchbay.core.store

import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DesktopStorageTest {
    private val dir: File = Files.createTempDirectory("pbdb").toFile()
    private val file = File(dir, "patchbay.db")

    private fun device(addr: String) = StoredDevice(
        DeviceId("govee-1f80c5323236724e"), "govee-lan", "1f80c5323236724e", "Govee H6076", DeviceKind.LIGHT, "Govee", "H6076",
        addr, 4003, ControlPath.LOCAL_AFTER_SETUP, null, 0, 0,
    )

    @Test
    fun roundTripsAndKeepsFirstSeen() {
        val key = ByteArray(32) { it.toByte() }
        DesktopStorage.open(file, key).use { driver ->
            val store = SqlHomeStore(PatchbayDb(driver))
            store.saveDevice(device("192.168.1.23"), now = 1_000)
            store.saveDevice(device("192.168.1.24"), now = 2_000)
            store.savePairing(StoredPairing(DeviceId("govee-1f80c5323236724e"), byteArrayOf(1, 2, 3), "ab12", 2_000))
            store.putSetting("home.name", "Casa")
        }
        DesktopStorage.open(file, key).use { driver ->
            val store = SqlHomeStore(PatchbayDb(driver))
            val d = store.devices().single()
            assertEquals("192.168.1.24", d.address)
            assertEquals(1_000, d.firstSeen)
            assertEquals(2_000, d.lastSeen)
            assertContentEquals(byteArrayOf(1, 2, 3), store.pairing(d.id)!!.secret)
            assertEquals("Casa", store.setting("home.name"))
            store.forget(d.id)
            assertEquals(0, store.devices().size)
        }
    }

    @Test
    fun fileIsEncryptedAndWrongKeyFails() {
        val key = ByteArray(32) { 7 }
        DesktopStorage.open(file, key).use { SqlHomeStore(PatchbayDb(it)).saveDevice(device("192.168.1.23"), 1) }
        val bytes = file.readBytes()
        // A plaintext SQLite file starts with "SQLite format 3"; an encrypted one must not, nor contain our data.
        assertFalse(String(bytes.copyOf(16), Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("192.168.1.23"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Govee"))
        assertFailsWith<Exception> {
            DesktopStorage.open(file, ByteArray(32) { 8 }).use { SqlHomeStore(PatchbayDb(it)).devices() }
        }
    }

    @Test
    fun dataKeyIsCreatedOnceAndReused() {
        val w = FileKeyWrapper(dir)
        val k1 = DataKey.loadOrCreate(dir, w)
        val k2 = DataKey.loadOrCreate(dir, w)
        assertContentEquals(k1, k2)
        assertEquals(32, k1.size)
        assertNotNull(File(dir, "dek.wrapped").takeIf { it.exists() })
    }
}
