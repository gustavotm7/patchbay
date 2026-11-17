package dev.patchbay.core.backup

import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.RoomId
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.store.HomeStore
import dev.patchbay.core.store.InMemoryHomeStore
import dev.patchbay.core.store.StoredDevice
import dev.patchbay.core.store.StoredPairing
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    private val pass = "correct horse battery".toCharArray()
    private val tuyaSecret = Random(1).nextBytes(48)
    private val castSecret = Random(2).nextBytes(2_300) // an RSA key is this big
    private val pinned = "3F:9A:8C:2E:1B:7D:4F:60:A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90:A1:B2:C3:D4:E5:F6:07:18"

    private fun device(id: String, name: String, kind: DeviceKind, address: String, adapter: String, key: String, room: String? = null) =
        StoredDevice(DeviceId(id), adapter, key, name, kind, "Vendor", "Model", address, 6668, ControlPath.LOCAL_AFTER_SETUP, room?.let(::RoomId), 1, 1)

    private fun sampleStore() = InMemoryHomeStore().apply {
        saveDevice(device("tuya-bf1234567890abcdef12", "Bedside lamp", DeviceKind.LIGHT, "192.168.1.30", "tuya-local", "bf1234567890abcdef12", "Bedroom"), 10)
        saveDevice(device("cast-9f1c2d3e4b5a6978", "Living room TV", DeviceKind.MEDIA_RECEIVER, "192.168.1.31", "cast", "9f1c2d3e4b5a6978"), 10)
        saveDevice(device("govee-1F80C5323236724E", "Shelf strip", DeviceKind.LIGHT, "192.168.1.32", "govee-lan", "1F80C5323236724E", "Living room"), 10)
        setRoom(DeviceId("tuya-bf1234567890abcdef12"), RoomId("Bedroom"))
        setRoom(DeviceId("govee-1F80C5323236724E"), RoomId("Living room"))
        savePairing(StoredPairing(DeviceId("tuya-bf1234567890abcdef12"), tuyaSecret, null, 11))
        savePairing(StoredPairing(DeviceId("cast-9f1c2d3e4b5a6978"), castSecret, pinned, 12))
        putSetting("scenes", """[{"id":"s1","name":"Movie night","steps":[]}]""")
        putSetting("automations", """[{"id":"a1","name":"Evening lights","sceneId":"s1","hour":19,"minute":30,"days":[1,2,3]}]""")
        putSetting("location", """{"lat":51.5,"lon":-0.12}""")
        putSetting("favourites", """["govee-1F80C5323236724E"]""")
        putSetting("ledger", """[{"t":1,"d":"Bedside lamp","e":"Turned on"}]""") // history stays behind
    }

    private fun snapshotOf(store: HomeStore) =
        store.devices().sortedBy { it.id.value }.map { d ->
            listOf(d.id.value, d.adapter, d.adapterKey, d.name, d.kind, d.vendor, d.model, d.address, d.port, d.controlPath, d.roomId?.value,
                store.pairing(d.id)?.let { Base64.getEncoder().encodeToString(it.secret) + "|" + it.pinnedSha256 + "|" + it.pairedAt })
        }

    private fun fast(store: HomeStore) = (Backup.createBytes(store, pass, 5, iterations = 1_000) as PatchbayResult.Ok).value

    @Test
    fun aBackupRestoresEverythingItCovers(): Unit {
        val original = sampleStore()
        val text = (Backup.create(original, pass, now = 99) as PatchbayResult.Ok).value
        assertTrue(text.startsWith("PATCHBAY-BACKUP-1:"))

        val fresh = InMemoryHomeStore()
        val restored = (Backup.restore(fresh, text, pass, now = 100) as PatchbayResult.Ok).value
        assertEquals(3, restored.devices); assertEquals(2, restored.pairings); assertEquals(4, restored.settings)

        assertEquals(snapshotOf(original), snapshotOf(fresh))
        assertEquals(original.setting("scenes"), fresh.setting("scenes"))
        assertEquals(original.setting("automations"), fresh.setting("automations"))
        assertEquals(original.setting("location"), fresh.setting("location"))
        assertEquals(original.setting("favourites"), fresh.setting("favourites"))
        assertNull(fresh.setting("ledger"), "the history stays behind")
        assertContentEquals(castSecret, fresh.pairing(DeviceId("cast-9f1c2d3e4b5a6978"))!!.secret)
    }

    @Test
    fun theBackupHoldsNoSecretNameAddressOrSettingInTheClear(): Unit {
        val text = (Backup.create(sampleStore(), pass, now = 99) as PatchbayResult.Ok).value
        val bytes = Base64.getDecoder().decode(text.removePrefix("PATCHBAY-BACKUP-1:"))
        val spellings = mutableListOf<String>()
        for (secret in listOf(tuyaSecret, castSecret)) {
            val hex = secret.joinToString("") { "%02x".format(it) }
            spellings += listOf(hex, hex.uppercase(), Base64.getEncoder().encodeToString(secret).take(40), Base64.getUrlEncoder().encodeToString(secret).take(40))
        }
        spellings += listOf("Bedside lamp", "Living room", "Shelf strip", "192.168.1.30", "192.168.1.31", "bf1234567890abcdef12", "9f1c2d3e4b5a6978", "1F80C5323236724E", "Movie night", "Evening lights", "Bedroom", pinned.take(20), "tuya-local", "scenes", "PBBK".repeat(0) + "cast")
        val haystacks = listOf(text, String(bytes, Charsets.ISO_8859_1), bytes.joinToString("") { "%02x".format(it) })
        for (needle in spellings) for (h in haystacks) assertFalse(needle in h, "the backup contains '${needle.take(12)}…'")
        // Raw secret bytes too (a window of them, since a whole one would have to match exactly).
        for (secret in listOf(tuyaSecret, castSecret)) assertFalse(String(bytes, Charsets.ISO_8859_1).contains(String(secret.copyOf(16), Charsets.ISO_8859_1)))
    }

    @Test
    fun twoBackupsOfTheSameHomeAreNotTheSameBytes(): Unit {
        val s = sampleStore()
        assertTrue(Backup.create(s, pass, 1) != Backup.create(s, pass, 1), "salt and nonce are random")
    }

    @Test
    fun aWrongPassphraseIsRefusedAndNothingChanges(): Unit {
        val text = (Backup.create(sampleStore(), pass, 1) as PatchbayResult.Ok).value
        val target = InMemoryHomeStore().apply { saveDevice(device("kasa-1", "Desk plug", DeviceKind.PLUG, "192.168.1.40", "kasa-legacy", "k1"), 1) }
        val before = snapshotOf(target)

        val r = Backup.restore(target, text, "correct horse batterY".toCharArray(), 2)

        assertIs<PatchbayError.Unauthorized>((r as PatchbayResult.Err).error)
        assertEquals(before, snapshotOf(target))
        assertNull(target.setting("scenes"))
    }

    @Test
    fun aShortPassphraseIsRefusedBeforeAnythingIsMade(): Unit {
        val r = Backup.create(sampleStore(), "short".toCharArray(), 1)
        assertIs<PatchbayError.InvalidArgument>((r as PatchbayResult.Err).error)
    }

    @Test
    fun aHomeTooBigToBackUpIsRefused(): Unit {
        val big = InMemoryHomeStore()
        for (i in 0..500) big.saveDevice(device("d$i", "D$i", DeviceKind.PLUG, "192.168.1.1", "kasa-legacy", "k$i"), 1)
        assertIs<PatchbayError.InvalidArgument>((Backup.create(big, pass, 1) as PatchbayResult.Err).error)
    }

    @Test
    fun restoreReplacesWhatWasThereAndKeepsRoomsAndPairings(): Unit {
        val text = (Backup.create(sampleStore(), pass, 1) as PatchbayResult.Ok).value
        val target = InMemoryHomeStore().apply {
            saveDevice(device("kasa-1", "Desk plug", DeviceKind.PLUG, "192.168.1.40", "kasa-legacy", "k1"), 1)
            savePairing(StoredPairing(DeviceId("kasa-1"), byteArrayOf(1, 2, 3), null, 1))
            saveDevice(device("govee-1F80C5323236724E", "Old name", DeviceKind.LIGHT, "192.168.1.99", "govee-lan", "1F80C5323236724E", "Attic"), 1)
            putSetting("ledger", "history")
            putSetting("quiet", "22:00-07:00")
        }

        assertTrue(Backup.restore(target, text, pass, 5) is PatchbayResult.Ok)

        assertNull(target.pairing(DeviceId("kasa-1")), "a device that is not in the backup is removed with its pairing")
        assertEquals(listOf("cast-9f1c2d3e4b5a6978", "govee-1F80C5323236724E", "tuya-bf1234567890abcdef12"), target.devices().map { it.id.value }.sorted())
        val govee = target.devices().first { it.id.value.startsWith("govee") }
        assertEquals("Shelf strip", govee.name); assertEquals("192.168.1.32", govee.address); assertEquals("Living room", govee.roomId?.value)
        assertEquals("history", target.setting("ledger"), "settings the backup does not cover are left alone")
        assertEquals("22:00-07:00", target.setting("quiet"), "so are settings it does not mention")
    }

    @Test
    fun anyDamageIsRefusedWithoutThrowingOrChangingAnything(): Unit {
        val file = fast(sampleStore())
        val target = InMemoryHomeStore().apply { saveDevice(device("kasa-1", "Desk plug", DeviceKind.PLUG, "192.168.1.40", "kasa-legacy", "k1"), 1) }
        val before = snapshotOf(target)
        val rnd = Random(20_261_117)
        var refused = 0
        repeat(10_000) { n ->
            val damaged = file.copyOf()
            val mutated: ByteArray = when (n % 5) {
                0 -> damaged.also { it[rnd.nextInt(it.size)] = (it[rnd.nextInt(it.size).coerceAtMost(it.size - 1)].toInt() xor (1 shl rnd.nextInt(8))).toByte() }.also { if (it.contentEquals(file)) it[0] = (it[0] + 1).toByte() }
                1 -> damaged.copyOf(rnd.nextInt(file.size)) // cut short
                2 -> damaged + rnd.nextBytes(rnd.nextInt(1, 64)) // extended
                3 -> damaged.also { for (k in 0 until rnd.nextInt(2, 6)) it[rnd.nextInt(it.size)] = rnd.nextInt().toByte() }
                else -> rnd.nextBytes(rnd.nextInt(0, 200)) // not ours at all
            }
            if (mutated.contentEquals(file)) return@repeat
            val r = Backup.restoreBytes(target, mutated, pass, 7, minIterations = 1)
            assertTrue(r is PatchbayResult.Err, "damaged file #$n was accepted")
            refused++
        }
        assertTrue(refused > 9_900)
        assertEquals(before, snapshotOf(target))
    }

    @Test
    fun theUndamagedFastFileStillOpensSoTheDamageTestMeansSomething(): Unit {
        val file = fast(sampleStore())
        assertTrue(Backup.restoreBytes(InMemoryHomeStore(), file, pass, 7, minIterations = 1) is PatchbayResult.Ok)
    }

    @Test
    fun aFileThatAsksForAbsurdKeyStretchingIsRefusedAtOnce(): Unit {
        val file = fast(sampleStore())
        for (iterations in listOf(Int.MAX_VALUE, 1_200_001, 99_999, 0, -5)) {
            val hostile = file.copyOf()
            java.nio.ByteBuffer.wrap(hostile).putInt(6, iterations)
            val started = System.nanoTime()
            val r = Backup.restoreBytes(InMemoryHomeStore(), hostile, pass, 7, minIterations = 100_000)
            assertTrue(r is PatchbayResult.Err)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 500, "refusing $iterations rounds must not do the work")
        }
    }

    @Test
    fun textThatIsNotABackupIsNotMistakenForOne(): Unit {
        val target = InMemoryHomeStore()
        for (text in listOf("", "hello", "PATCHBAY-BACKUP-1:", "PATCHBAY-BACKUP-1:!!!not base64!!!", "PATCHBAY-BACKUP-2:AAAA", "x".repeat(6 * 1024 * 1024), "PATCHBAY-BACKUP-1:" + "A".repeat(6 * 1024 * 1024))) {
            assertTrue(Backup.restore(target, text, pass, 1) is PatchbayResult.Err, "accepted: ${text.take(30)}")
        }
        assertEquals(emptyList(), target.devices())
    }

    @Test
    fun pastingThatWrapsTheLineStillWorks(): Unit {
        val text = (Backup.create(sampleStore(), pass, 1) as PatchbayResult.Ok).value
        val wrapped = text.chunked(76).joinToString("\r\n") + "\n  "
        assertTrue(Backup.restore(InMemoryHomeStore(), wrapped, pass, 2) is PatchbayResult.Ok)
    }

    @Test
    fun aValidlyEncryptedButHostilePayloadIsRefusedBeforeAnythingIsWritten(): Unit {
        fun payload(devices: String, pairings: String = "[]", settings: String = "{}") = """{"createdAt":1,"devices":$devices,"pairings":$pairings,"settings":$settings}"""
        fun dev(id: String = "d1", name: String = "Lamp", kind: String = "LIGHT", port: Int = 80, address: String = "192.168.1.5") =
            """{"id":"$id","adapter":"a","adapterKey":"k","name":"$name","kind":"$kind","vendor":null,"model":null,"address":"$address","port":$port,"controlPath":"LOCAL","room":null}"""
        val ok = payload("[${dev()}]")
        val bad = listOf(
            payload("[${dev(kind = "TOASTER")}]"),
            payload("[${dev(name = "x".repeat(65))}]"),
            payload("[${dev(name = "bad\\u0007name")}]"),
            payload("[${dev(port = 70000)}]"),
            payload("[${dev()},${dev()}]"),
            payload("[${dev()}]", pairings = """[{"deviceId":"other","secret":"AAAA","pinned":null,"pairedAt":1}]"""),
            payload("[${dev()}]", pairings = """[{"deviceId":"d1","secret":"not base64!","pinned":null,"pairedAt":1}]"""),
            payload("[${dev()}]", settings = """{"scenes":"${"y".repeat(1024 * 1024 + 1)}"}"""),
            "not json at all", """{"createdAt":"x"}""", payload("[${(1..501).joinToString(",") { dev(id = "d$it") }}]"),
        )
        val target = InMemoryHomeStore().apply { saveDevice(device("kasa-1", "Desk plug", DeviceKind.PLUG, "192.168.1.40", "kasa-legacy", "k1"), 1) }
        val before = snapshotOf(target)
        assertTrue(Backup.restoreBytes(InMemoryHomeStore(), Backup.seal(ok.toByteArray(), pass, 1_000), pass, 1, 1) is PatchbayResult.Ok, "the control payload should be accepted")
        for ((i, p) in bad.withIndex()) {
            val r = Backup.restoreBytes(target, Backup.seal(p.toByteArray(), pass, 1_000), pass, 1, 1)
            assertTrue(r is PatchbayResult.Err, "hostile payload #$i was accepted")
        }
        assertEquals(before, snapshotOf(target))
    }

    @Test
    fun settingsThatAreNotOnTheListAreIgnored(): Unit {
        val p = """{"createdAt":1,"devices":[],"pairings":[],"settings":{"scenes":"[]","evil":"x","ledger":"history"}}"""
        val target = InMemoryHomeStore()
        val r = (Backup.restoreBytes(target, Backup.seal(p.toByteArray(), pass, 1_000), pass, 1, 1) as PatchbayResult.Ok).value
        assertEquals(1, r.settings)
        assertEquals("[]", target.setting("scenes")); assertNull(target.setting("evil")); assertNull(target.setting("ledger"))
    }

    @Test
    fun aPassphraseTypedWithDifferentUnicodeFormsOpensTheSameBackup(): Unit {
        val composed = "paésword-éé".toCharArray()      // é as one character
        val decomposed = "paésword-éé".toCharArray() // é as e + accent
        val file = (Backup.createBytes(sampleStore(), composed, 1, iterations = 1_000) as PatchbayResult.Ok).value
        assertTrue(Backup.restoreBytes(InMemoryHomeStore(), file, decomposed, 1, minIterations = 1) is PatchbayResult.Ok)
    }
}
