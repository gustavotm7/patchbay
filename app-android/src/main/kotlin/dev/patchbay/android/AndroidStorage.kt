package dev.patchbay.android

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.patchbay.core.db.PatchbayDb
import dev.patchbay.core.store.KeyWrapper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the database key with an AES-256-GCM key that never leaves Android Keystore (ADR 0003).
 * StrongBox is used when the device has it, falling back to the TEE. The key is only usable while the device is unlocked.
 */
class KeystoreKeyWrapper(context: Context) : KeyWrapper {
    private val strongBoxAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private val key: SecretKey = (keyStore.getKey(ALIAS, null) as? SecretKey) ?: generate()
    private var strongBox = strongBoxAvailable

    override val kind: String get() = if (strongBox) "android-keystore-strongbox" else "android-keystore"
    override val osProtected: Boolean = true

    private fun generate(): SecretKey {
        fun spec(sb: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(sb)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            gen.init(spec(strongBoxAvailable))
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            strongBox = false
            gen.init(spec(false))
            gen.generateKey()
        }
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        require(iv.size == IV_BYTES)
        return iv + c.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_BYTES)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, IV_BYTES))
        return c.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "patchbay-dek-wrap-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** Opens the SQLCipher-encrypted database with a raw 256-bit key (no PBKDF2; the key is already random). */
object AndroidStorage {
    fun open(context: Context, file: File, key: ByteArray): SqlDriver {
        System.loadLibrary("sqlcipher")
        val rawKey = "x'" + key.joinToString("") { "%02x".format(it) } + "'"
        val factory = SupportOpenHelperFactory(rawKey.toByteArray(Charsets.US_ASCII))
        return AndroidSqliteDriver(PatchbayDb.Schema, context, file.absolutePath, factory)
    }
}
