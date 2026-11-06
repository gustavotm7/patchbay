package dev.patchbay.core.adapter.tuya

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-ECB with PKCS#7 padding, keyed by the device's 16-character local key (Tuya protocol 3.3). ECB leaks
 * patterns and gives no integrity beyond the frame CRC; that is the protocol's design, not ours, and it is why this
 * only ever runs inside a LAN-only NetGuard connection.
 */
internal object TuyaCipher {
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, key, plain)

    /** Throws [javax.crypto.BadPaddingException] or [javax.crypto.IllegalBlockSizeException] on a wrong key or corrupt data. */
    fun decrypt(key: ByteArray, cipher: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, key, cipher)

    private fun run(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "local key must be 16 bytes" }
        return Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES")) }.doFinal(data)
    }
}
