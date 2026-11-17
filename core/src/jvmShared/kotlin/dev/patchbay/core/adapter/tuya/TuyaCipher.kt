package dev.patchbay.core.adapter.tuya

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The AES and HMAC pieces of the Tuya local protocol, keyed by the device's 16-character local key (or the session key derived from it).
 *
 * Protocol 3.3 and 3.4 use AES-128-ECB with PKCS#7 padding. ECB leaks patterns and gives no integrity of its own (3.3 has a CRC, 3.4 an HMAC-SHA256
 * over the frame); that is the protocol's design, not ours, and it is why this only ever runs inside a LAN-only NetGuard connection. Protocol 3.5 uses
 * AES-128-GCM with the frame header as authenticated data.
 */
internal object TuyaCipher {
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray = ecb(Cipher.ENCRYPT_MODE, key, plain, "AES/ECB/PKCS5Padding")

    /** Throws [javax.crypto.BadPaddingException] or [javax.crypto.IllegalBlockSizeException] on a wrong key or corrupt data. */
    fun decrypt(key: ByteArray, cipher: ByteArray): ByteArray = ecb(Cipher.DECRYPT_MODE, key, cipher, "AES/ECB/PKCS5Padding")

    /** One or more whole blocks, no padding (how the 3.4 session key is derived). */
    fun encryptBlocks(key: ByteArray, blocks: ByteArray): ByteArray {
        require(blocks.size % 16 == 0) { "whole blocks only" }
        return ecb(Cipher.ENCRYPT_MODE, key, blocks, "AES/ECB/NoPadding")
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { update(it) } }.doFinal()

    /** Constant-time comparison for checksums, tags and nonces' HMACs. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** AES-128-GCM: returns the ciphertext followed by the 16-byte tag. [iv] is 12 bytes. */
    fun gcmSeal(key: ByteArray, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 16 && iv.size == 12) { "key is 16 bytes and the nonce 12" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            if (aad.isNotEmpty()) updateAAD(aad)
        }.doFinal(plain)
    }

    /** Throws [javax.crypto.AEADBadTagException] when the tag, the header or the key is wrong. [sealed] is the ciphertext followed by the tag. */
    fun gcmOpen(key: ByteArray, iv: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(key.size == 16 && iv.size == 12) { "key is 16 bytes and the nonce 12" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            if (aad.isNotEmpty()) updateAAD(aad)
        }.doFinal(sealed)
    }

    private fun ecb(mode: Int, key: ByteArray, data: ByteArray, transformation: String): ByteArray {
        require(key.size == 16) { "local key must be 16 bytes" }
        return Cipher.getInstance(transformation).apply { init(mode, SecretKeySpec(key, "AES")) }.doFinal(data)
    }
}
