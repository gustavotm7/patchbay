package dev.patchbay.core.adapter.homekit

import java.math.BigInteger
import java.security.MessageDigest

/**
 * X25519 (RFC 7748) and Ed25519 (RFC 8032) on [BigInteger].
 *
 * Why hand-written: Android's JCA only offers these curves from API 33 and the app supports API 31+, and a crypto
 * library would be a new dependency in a path that handles pairing keys. The maths is the textbook form, checked
 * against the RFC test vectors and cross-checked against the JDK's own implementations in the tests.
 * It is NOT constant-time (BigInteger isn't); the only attacker who could exploit that is one who can time operations
 * on this very device, which is outside the threat model (docs/THREAT_MODEL.md).
 */
internal object Curve25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val A24: BigInteger = BigInteger.valueOf(121665)

    private fun littleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun toLittleEndian(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(be.size <= len) { "value too large" }
        val out = ByteArray(len)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }

    /** RFC 7748 §5: scalar multiplication on the Montgomery curve. [scalar] and [u] are 32 bytes, little-endian. */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32) { "x25519 inputs must be 32 bytes" }
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()
        val kInt = littleEndian(k)
        val uc = u.copyOf(); uc[31] = (uc[31].toInt() and 127).toByte()
        val x1 = littleEndian(uc).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = kInt.testBit(t)
            if (swap != bit) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = bit
            val a = (x2 + z2).mod(P); val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P); val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P); val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P); val cb = (c * b).mod(P)
            x3 = (da + cb).let { (it * it).mod(P) }
            z3 = (da - cb).let { (it * it).mod(P) }.let { (x1 * it).mod(P) }
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e).mod(P)).mod(P)
        }
        if (swap) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        val result = (x2 * z2.modPow(P - BigInteger.TWO, P)).mod(P)
        return toLittleEndian(result, 32)
    }

    private val BASE_U = ByteArray(32).also { it[0] = 9 }
    fun x25519PublicKey(privateKey: ByteArray): ByteArray = x25519(privateKey, BASE_U)

    // ---- Ed25519 ----

    private val D: BigInteger = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)

    /** Extended homogeneous coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun add(p: Pt, q: Pt): Pt {
        val a = ((p.y - p.x) * (q.y - q.x)).mod(P)
        val b = ((p.y + p.x) * (q.y + q.x)).mod(P)
        val c = (p.t * BigInteger.TWO * D * q.t).mod(P)
        val d = (p.z * BigInteger.TWO * q.z).mod(P)
        val e = b - a; val f = d - c; val g = d + c; val h = b + a
        return Pt((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun mul(s: BigInteger, p: Pt): Pt {
        var r = IDENTITY; var q = p
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun equal(p: Pt, q: Pt): Boolean =
        (p.x * q.z - q.x * p.z).mod(P).signum() == 0 && (p.y * q.z - q.y * p.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * (D * y * y + BigInteger.ONE).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * SQRT_M1).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P - x
        return x
    }

    private val BASE: Pt = run {
        val y = (BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0)!!
        Pt(x, y, BigInteger.ONE, (x * y).mod(P))
    }

    private fun compress(p: Pt): ByteArray {
        val zi = p.z.modInverse(P)
        val x = (p.x * zi).mod(P); val y = (p.y * zi).mod(P)
        val out = toLittleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decompress(b: ByteArray): Pt? {
        if (b.size != 32) return null
        val sign = (b[31].toInt() ushr 7) and 1
        val c = b.copyOf(); c[31] = (c[31].toInt() and 0x7f).toByte()
        val y = littleEndian(c)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, (x * y).mod(P))
    }

    private fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").also { md -> parts.forEach { md.update(it) } }.digest()

    private fun clampedScalar(h: ByteArray): BigInteger {
        val a = h.copyOfRange(0, 32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = ((a[31].toInt() and 127) or 64).toByte()
        return littleEndian(a)
    }

    fun ed25519PublicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "ed25519 seed must be 32 bytes" }
        return compress(mul(clampedScalar(sha512(seed)), BASE))
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "ed25519 seed must be 32 bytes" }
        val h = sha512(seed)
        val a = clampedScalar(h)
        val pub = compress(mul(a, BASE))
        val r = littleEndian(sha512(h.copyOfRange(32, 64), message)).mod(L)
        val rEnc = compress(mul(r, BASE))
        val k = littleEndian(sha512(rEnc, pub, message)).mod(L)
        val s = (r + k * a).mod(L)
        return rEnc + toLittleEndian(s, 32)
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rEnc = signature.copyOfRange(0, 32)
        val r = decompress(rEnc) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val k = littleEndian(sha512(rEnc, publicKey, message)).mod(L)
        return equal(mul(s, BASE), add(r, mul(k, a)))
    }
}
