package dev.patchbay.core.crypto

import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tries every platform cryptography algorithm Patchbay depends on, once, with a real operation, and says which work. Android's names and providers differ from the JDK's
 * (ChaCha20-Poly1305 is spelled differently), so a feature that cannot work on a phone shows here instead of failing deep inside pairing. The result goes into
 * Copy diagnostics ("Crypto"). Nothing secret is involved: fixed test inputs only.
 */
public object CryptoSelfCheck {
    /** Worked out once per process (generating an RSA key is not free on a phone). */
    public fun lines(): List<String> = results

    private val results: List<String> by lazy {
        checks.map { (name, block) ->
            val result = try { block(); "ok" } catch (e: Throwable) { "MISSING or failing (${e.javaClass.simpleName})" }
            "$name: $result"
        }
    }

    private val key16 = ByteArray(16) { it.toByte() }
    private val key32 = ByteArray(32) { it.toByte() }
    private val nonce12 = ByteArray(12)

    private val checks: List<Pair<String, () -> Unit>> = listOf(
        "ChaCha20-Poly1305 (HomeKit)" to {
            val name = listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding").first { n -> try { Cipher.getInstance(n); true } catch (e: java.security.GeneralSecurityException) { false } }
            val c = Cipher.getInstance(name).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32, "ChaCha20"), IvParameterSpec(nonce12)) }
            check(c.doFinal(ByteArray(8)).size == 8 + 16)
        },
        "AES/GCM/NoPadding (storage, backup)" to {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"), GCMParameterSpec(128, nonce12)) }
            check(c.doFinal(ByteArray(8)).size == 8 + 16)
        },
        "AES/ECB/PKCS5Padding (Tuya 3.3 and 3.4)" to { check(Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES")) }.doFinal(ByteArray(3)).size == 16) },
        "PBKDF2 with HMAC-SHA256 (backup, PIN)" to { check(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("x".toCharArray(), key16, 2, 256)).encoded.size == 32) },
        "HmacSHA256 and HmacSHA512 (Tuya, HomeKit)" to { listOf("HmacSHA256", "HmacSHA512").forEach { Mac.getInstance(it).apply { init(SecretKeySpec(key16, it)) }.doFinal(ByteArray(1)) } },
        "SHA-256 and SHA-512" to { listOf("SHA-256", "SHA-512").forEach { MessageDigest.getInstance(it).digest(ByteArray(1)) } },
        "RSA signature, SHA256withRSA (Android TV pairing)" to {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(ByteArray(1)) }.sign()
        },
        "PKCS12 key store (Android TV pairing)" to { KeyStore.getInstance("PKCS12") },
    )
}
