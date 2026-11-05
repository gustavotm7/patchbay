package dev.patchbay.core.lock

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN storage for platforms without a system authenticator (Windows, until Hello lands).
 * PBKDF2-HMAC-SHA256, 310 000 iterations, 16-byte salt, constant-time comparison.
 * The encoded hash lives in the encrypted database (settings), never in a plain file.
 */
public object PinHash {
    private const val ITERATIONS = 310_000
    private const val PREFIX = "pbkdf2-sha256"
    private const val SEP = "|"

    public fun isValidPin(pin: String): Boolean = pin.length in 4..12 && pin.all { it in '0'..'9' }

    public fun hash(pin: String, iterations: Int = ITERATIONS): String {
        require(isValidPin(pin)) { "PIN must be 4–12 digits" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val dk = derive(pin, salt, iterations)
        val b64 = Base64.getEncoder().withoutPadding()
        return listOf(PREFIX, iterations.toString(), b64.encodeToString(salt), b64.encodeToString(dk)).joinToString(SEP)
    }

    public fun verify(pin: String, encoded: String): Boolean {
        val parts = encoded.split(SEP)
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 10_000..5_000_000 } ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        if (!isValidPin(pin)) return false
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual).also { actual.fill(0) }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
