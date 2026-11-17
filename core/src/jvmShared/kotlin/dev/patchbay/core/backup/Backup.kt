package dev.patchbay.core.backup

import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.RoomId
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.store.HomeStore
import dev.patchbay.core.store.StoredDevice
import dev.patchbay.core.store.StoredPairing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * An encrypted, passphrase-protected copy of what it takes to get back to a working home on a new phone or laptop: the devices, their pairings
 * (the keys, codes and pinned certificates, which are the secrets), rooms, scenes, automations and the owner's settings (ADR 0013).
 *
 * Nothing here touches the network and nothing is made unless the owner asks. The file is `PBBK`, a version, the key-derivation settings, a random
 * salt and nonce, then AES-256-GCM of a JSON document, with the header as authenticated data. The key comes from the passphrase with
 * PBKDF2-HMAC-SHA256 (600,000 rounds). A wrong passphrase and a damaged file look the same on purpose. Everything read back is bounded and
 * checked **before** anything is written, so a bad file changes nothing.
 */
public object Backup {
    /** The text form starts with this, then the file as Base64, on one line, so it can be copied and pasted. */
    public const val TEXT_PREFIX: String = "PATCHBAY-BACKUP-1:"
    public const val MIN_PASSPHRASE_LENGTH: Int = 8
    public const val DEFAULT_ITERATIONS: Int = 600_000
    public const val MAX_FILE_BYTES: Int = 4 * 1024 * 1024

    /** Only these settings travel. History (the activity list, the graphs) stays behind: it is not needed to get going again. */
    internal val SETTING_KEYS: List<String> = listOf("scenes", "automations", "adaptive", "away", "location", "favourites", "quiet")

    private val MAGIC = "PBBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val KDF_PBKDF2_SHA256 = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 4 + 1 + 1 + 4 + SALT_BYTES + NONCE_BYTES
    private const val MAX_ITERATIONS = 1_200_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_PLAIN_BYTES = 3 * 1024 * 1024
    private const val MAX_DEVICES = 500
    private const val MAX_SHORT = 256
    private const val MAX_SECRET_BASE64 = 96 * 1024
    private const val MAX_SETTING = 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    @Serializable
    internal class BackupDevice(
        val id: String, val adapter: String, val adapterKey: String, val name: String, val kind: String, val vendor: String?, val model: String?,
        val address: String, val port: Int?, val controlPath: String, val room: String?,
    )

    @Serializable
    internal class BackupPairing(val deviceId: String, val secret: String, val pinned: String?, val pairedAt: Long)

    @Serializable
    internal class Payload(val createdAt: Long, val devices: List<BackupDevice>, val pairings: List<BackupPairing>, val settings: Map<String, String>)

    /** What a restore put back, in numbers, for the message the owner sees. */
    public class Restored(public val devices: Int, public val pairings: Int, public val settings: Int)

    /** The backup as one line of text. Fails (writing nothing anywhere) for a short passphrase or a home that is too big to back up. */
    public fun create(store: HomeStore, passphrase: CharArray, now: Long): PatchbayResult<String> =
        when (val r = createBytes(store, passphrase, now, DEFAULT_ITERATIONS)) {
            is PatchbayResult.Ok -> PatchbayResult.Ok(TEXT_PREFIX + Base64.getEncoder().encodeToString(r.value))
            is PatchbayResult.Err -> r
        }

    /** Puts a backup made by [create] back, replacing the devices, pairings, scenes, automations and settings it covers. The store is untouched if anything is wrong. */
    public fun restore(store: HomeStore, text: String, passphrase: CharArray, now: Long): PatchbayResult<Restored> {
        val body = text.trim()
        if (!body.startsWith(TEXT_PREFIX)) return PatchbayResult.Err(PatchbayError.InvalidArgument("That isn't a Patchbay backup"))
        // Pasting can wrap the line; the text is Base64, so whitespace is never part of it.
        val encoded = body.removePrefix(TEXT_PREFIX).filterNot { it.isWhitespace() }
        if (encoded.length > MAX_FILE_BYTES * 4 / 3 + 8) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is too big to be one of ours"))
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))
        }
        return restoreBytes(store, bytes, passphrase, now, MIN_ITERATIONS)
    }

    internal fun createBytes(store: HomeStore, passphrase: CharArray, now: Long, iterations: Int): PatchbayResult<ByteArray> {
        if (passphrase.size < MIN_PASSPHRASE_LENGTH) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("Use a passphrase of at least $MIN_PASSPHRASE_LENGTH characters"))
        }
        val stored = store.devices()
        if (stored.size > MAX_DEVICES) return PatchbayResult.Err(PatchbayError.InvalidArgument("There are too many devices to back up"))
        val devices = stored.map { d ->
            BackupDevice(d.id.value, d.adapter, d.adapterKey, d.name, d.kind.name, d.vendor, d.model, d.address, d.port, d.controlPath.name, d.roomId?.value)
        }
        val pairings = stored.mapNotNull { d ->
            store.pairing(d.id)?.let { BackupPairing(it.deviceId.value, Base64.getEncoder().encodeToString(it.secret), it.pinnedSha256, it.pairedAt) }
        }
        val settings = SETTING_KEYS.mapNotNull { k -> store.setting(k)?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        val plain = json.encodeToString(Payload(now, devices, pairings, settings)).toByteArray(Charsets.UTF_8)
        if (plain.size > MAX_PLAIN_BYTES) return PatchbayResult.Err(PatchbayError.InvalidArgument("There is too much to back up"))
        return PatchbayResult.Ok(seal(plain, passphrase, iterations))
    }

    /** Header, then the encrypted document. The header is authenticated, so changing any of it makes the file unreadable. */
    internal fun seal(plain: ByteArray, passphrase: CharArray, iterations: Int): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION.toByte()).put(KDF_PBKDF2_SHA256.toByte()).putInt(iterations).put(salt).put(nonce).array()
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header)
        }
        key.fill(0)
        return header + cipher.doFinal(plain)
    }

    /** The decrypted document, or an error that does not say whether the passphrase or the file was at fault. [minIterations] is lowered only by tests. */
    internal fun unseal(file: ByteArray, passphrase: CharArray, minIterations: Int): PatchbayResult<ByteArray> {
        if (file.size > MAX_FILE_BYTES) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is too big to be one of ours"))
        if (file.size < HEADER_BYTES + 16) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))
        val buf = ByteBuffer.wrap(file)
        val magic = ByteArray(4).also(buf::get)
        val version = buf.get().toInt()
        val kdf = buf.get().toInt()
        val iterations = buf.getInt()
        if (!magic.contentEquals(MAGIC) || version != VERSION || kdf != KDF_PBKDF2_SHA256) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("That isn't a backup this version of Patchbay can read"))
        }
        // A hostile file must not be able to make this spend minutes on key stretching.
        if (iterations !in minIterations..MAX_ITERATIONS) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))
        val salt = ByteArray(SALT_BYTES).also(buf::get)
        val nonce = ByteArray(NONCE_BYTES).also(buf::get)
        val key = deriveKey(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(file, 0, HEADER_BYTES)
            }
            PatchbayResult.Ok(cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES))
        } catch (e: GeneralSecurityException) {
            PatchbayResult.Err(PatchbayError.Unauthorized("That passphrase doesn't open this backup, or the backup is damaged"))
        } finally {
            key.fill(0)
        }
    }

    internal fun restoreBytes(store: HomeStore, file: ByteArray, passphrase: CharArray, now: Long, minIterations: Int): PatchbayResult<Restored> {
        val plain = when (val r = unseal(file, passphrase, minIterations)) {
            is PatchbayResult.Ok -> r.value
            is PatchbayResult.Err -> return r
        }
        if (plain.size > MAX_PLAIN_BYTES) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))
        val payload = try {
            json.decodeFromString<Payload>(plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))
        }
        // Everything is checked before the first write, so a backup that is wrong in any way changes nothing.
        val devices = payload.devices.map { d -> toStoredDevice(d) ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged")) }
        if (devices.size > MAX_DEVICES || payload.pairings.size > MAX_DEVICES) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))
        if (devices.map { it.id }.toSet().size != devices.size) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))
        val known = devices.map { it.id.value }.toSet()
        val pairings = payload.pairings.map { p -> toStoredPairing(p, known) ?: return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged")) }
        val settings = payload.settings.filterKeys { it in SETTING_KEYS }
        if (settings.values.any { it.length > MAX_SETTING }) return PatchbayResult.Err(PatchbayError.InvalidArgument("That backup is damaged"))

        for (existing in store.devices()) if (existing.id !in devices.map { it.id }.toSet()) store.forget(existing.id)
        for (d in devices) {
            store.saveDevice(d, now)
            store.setRoom(d.id, d.roomId)
        }
        for (p in pairings) store.savePairing(p)
        for ((k, v) in settings) store.putSetting(k, v)
        return PatchbayResult.Ok(Restored(devices.size, pairings.size, settings.size))
    }

    private fun short(s: String?, max: Int = MAX_SHORT): Boolean = s == null || (s.length <= max && s.none { it.isISOControl() })

    private fun toStoredDevice(d: BackupDevice): StoredDevice? {
        if (d.id.isEmpty() || !short(d.id) || !short(d.adapter) || !short(d.adapterKey) || !short(d.name, 64) || !short(d.vendor) || !short(d.model) || !short(d.address, 64) || !short(d.room, 64)) return null
        if (d.port != null && d.port !in 0..65_535) return null
        val kind = DeviceKind.entries.firstOrNull { it.name == d.kind } ?: return null
        val path = ControlPath.entries.firstOrNull { it.name == d.controlPath } ?: return null
        return StoredDevice(DeviceId(d.id), d.adapter, d.adapterKey, d.name, kind, d.vendor, d.model, d.address, d.port, path, d.room?.let(::RoomId), 0, 0)
    }

    private fun toStoredPairing(p: BackupPairing, known: Set<String>): StoredPairing? {
        if (p.deviceId !in known || p.secret.length > MAX_SECRET_BASE64 || !short(p.pinned)) return null
        val secret = try { Base64.getDecoder().decode(p.secret) } catch (e: IllegalArgumentException) { return null }
        return StoredPairing(DeviceId(p.deviceId), secret, p.pinned, p.pairedAt)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val normalised = Normalizer.normalize(String(passphrase), Normalizer.Form.NFKC).toCharArray()
        val spec = PBEKeySpec(normalised, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            normalised.fill('\u0000')
        }
    }
}
