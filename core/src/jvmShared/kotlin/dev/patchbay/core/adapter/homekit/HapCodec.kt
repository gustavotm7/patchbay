package dev.patchbay.core.adapter.homekit

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HAP TLV8: type, length (0..255), value. Values over 255 bytes are split into consecutive same-type items. */
internal object Tlv8 {
    const val METHOD = 0; const val IDENTIFIER = 1; const val SALT = 2; const val PUBLIC_KEY = 3; const val PROOF = 4
    const val ENCRYPTED_DATA = 5; const val STATE = 6; const val ERROR = 7; const val RETRY_DELAY = 8
    const val CERTIFICATE = 9; const val SIGNATURE = 10; const val PERMISSIONS = 11; const val SEPARATOR = 255

    /** Largest message we will parse. Pair-setup messages are well under 2 KiB. */
    const val MAX_BYTES: Int = 16 * 1024

    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            require(type in 0..255) { "bad TLV type" }
            if (type == SEPARATOR) { out.write(SEPARATOR); out.write(0); continue }
            var off = 0
            if (value.isEmpty()) { out.write(type); out.write(0); continue }
            while (off < value.size) {
                val n = minOf(255, value.size - off)
                out.write(type); out.write(n); out.write(value, off, n)
                off += n
            }
        }
        return out.toByteArray()
    }

    /** Strict decode: returns null for truncated or oversize input. Adjacent items of the same type are joined. */
    fun decode(data: ByteArray): List<Pair<Int, ByteArray>>? {
        if (data.size > MAX_BYTES) return null
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        var lastType = -1
        while (i < data.size) {
            if (i + 2 > data.size) return null
            val type = data[i].toInt() and 0xff
            val len = data[i + 1].toInt() and 0xff
            if (i + 2 + len > data.size) return null
            val value = data.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
            if (type == lastType && type != SEPARATOR && result.isNotEmpty()) {
                result[result.size - 1] = type to (result.last().second + value)
            } else {
                result += type to value
            }
            lastType = type
        }
        return result
    }

    fun map(data: ByteArray): Map<Int, ByteArray>? = decode(data)?.toMap()
}

internal object Hkdf {
    /** HKDF-SHA512 (RFC 5869), as HAP uses it. */
    fun sha512(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..64) { "length" }
        val prk = hmac(salt, input)
        // One block is enough for HAP (32 bytes); loop for completeness.
        var t = ByteArray(0); val out = ByteArrayOutputStream(); var counter = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte())); out.write(t); counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA512")) }.doFinal(data)
}

/** ChaCha20-Poly1305 AEAD (RFC 7539) via the platform provider (JDK 11+, Android 9+). Output is ciphertext followed by the 16-byte tag. */
internal object Aead {
    const val TAG = 16

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce).apply { updateAAD(aad) }.doFinal(plain)

    /** Null when authentication fails. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < TAG) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, nonce).apply { updateAAD(aad) }.doFinal(sealed)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        require(key.size == 32 && nonce.size == 12) { "key/nonce size" }
        return Cipher.getInstance("ChaCha20-Poly1305").apply { init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce)) }
    }

    /** HAP's 12-byte nonce for pairing messages: four zero bytes then the ASCII label ("PS-Msg05"). */
    fun labelNonce(label: String): ByteArray = ByteArray(4) + label.toByteArray(Charsets.US_ASCII)

    /** HAP's session nonce: four zero bytes then the 64-bit little-endian frame counter. */
    fun counterNonce(counter: Long): ByteArray = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (counter ushr (8 * i)).toByte() }
}

/**
 * SRP-6a client as HAP specifies it: 3072-bit group (RFC 5054), g = 5, SHA-512, fixed k, user name "Pair-Setup".
 * [privateKey] is injectable for tests; production passes 16 random bytes.
 */
internal class SrpClient(private val setupCode: String, private val salt: ByteArray, serverPublic: ByteArray, private val privateKey: ByteArray) {
    companion object {
        const val USER = "Pair-Setup"
        const val LEN = 384

        val N: BigInteger = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        val G: BigInteger = BigInteger.valueOf(5)

        fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").also { md -> parts.forEach { md.update(it) } }.digest()
        fun unsigned(v: BigInteger): ByteArray = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        fun padded(v: BigInteger, len: Int = LEN): ByteArray = unsigned(v).let { ByteArray(len - it.size) + it }
        fun padded(b: ByteArray, len: Int = LEN): ByteArray = ByteArray(len - b.size) + b

        /** k = H(N | PAD(g)); HAP's value is fixed because N and g are. */
        val K: BigInteger = BigInteger(1, sha512(unsigned(N), padded(G)))

        fun x(salt: ByteArray, setupCode: String): BigInteger =
            BigInteger(1, sha512(salt, sha512("$USER:$setupCode".toByteArray())))
    }

    private val a = BigInteger(1, privateKey)
    private val bPub = BigInteger(1, serverPublic)
    val publicKey: BigInteger = G.modPow(a, N)
    val publicKeyBytes: ByteArray get() = unsigned(publicKey)

    /** The server's public value must not be 0 mod N (RFC 5054); otherwise the shared secret would be predictable. */
    val serverPublicValid: Boolean = bPub.mod(N).signum() != 0 && serverPublic.size <= LEN

    private val u = BigInteger(1, sha512(padded(publicKey), padded(bPub)))
    private val xv = x(salt, setupCode)
    private val s: BigInteger by lazy {
        val v = G.modPow(xv, N)
        val base = (bPub - K * v).mod(N)
        base.modPow(a + u * xv, N)
    }

    /** Session key K = H(S), 64 bytes: the input to every HKDF in pair-setup. */
    val sessionKey: ByteArray by lazy { sha512(padded(s)) }

    val clientProof: ByteArray by lazy {
        val hGroup = sha512(unsigned(N)).zip(sha512(unsigned(G))) { p, q -> (p.toInt() xor q.toInt()).toByte() }.toByteArray()
        sha512(hGroup, sha512(USER.toByteArray()), salt, padded(publicKey), padded(bPub), sessionKey)
    }

    /** The accessory's proof M2 = H(A | M1 | K). */
    fun verifyServerProof(proof: ByteArray): Boolean =
        MessageDigest.isEqual(sha512(padded(publicKey), clientProof, sessionKey), proof)
}
