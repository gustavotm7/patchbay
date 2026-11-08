package dev.patchbay.core.adapter.homekit

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HapCodecTest {
    @Test
    fun tlvFragmentsAndJoinsLongValues() {
        val wire = hex("060103") + hex("09ff") + ByteArray(255) { 0x61 } + hex("092d") + ByteArray(45) { 0x61 } + hex("010568656c6c6f")
        val items = Tlv8.decode(wire)!!
        assertEquals(listOf(6, 9, 1), items.map { it.first })
        assertEquals(300, items[1].second.size)
        assertContentEquals("hello".toByteArray(), items[2].second)
        assertContentEquals(wire, Tlv8.encode(items)) // and back, byte for byte
    }

    @Test
    fun tlvRejectsTruncatedAndOversizeInput() {
        assertNull(Tlv8.decode(hex("0605")))
        assertNull(Tlv8.decode(hex("06")))
        assertNull(Tlv8.decode(ByteArray(Tlv8.MAX_BYTES + 1)))
        assertEquals(emptyList(), Tlv8.decode(ByteArray(0)))
    }

    @Test
    fun tlvSeparatorKeepsNeighboursApart() {
        val enc = Tlv8.encode(listOf(1 to byteArrayOf(1), Tlv8.SEPARATOR to ByteArray(0), 1 to byteArrayOf(2)))
        assertEquals("010101ff00010102", enc.toHex())
        assertEquals(3, Tlv8.decode(enc)!!.size)
    }

    @Test
    fun tlvSurvivesRandomInput() {
        val rnd = Random(3)
        repeat(25_000) {
            val data = rnd.nextBytes(rnd.nextInt(0, 80))
            val items = Tlv8.decode(data)
            if (items != null) assertNotNull(Tlv8.decode(Tlv8.encode(items)))
        }
    }

    @Test
    fun hkdfMatchesAiohomekitVector() {
        assertEquals(
            "8f433176e34e8ca29c94aa61cef5229424372f7871bf8c3b4de9e2a54ef9e508",
            Hkdf.sha512(ByteArray(32) { '1'.code.toByte() }, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray()).toHex(),
        )
    }

    // RFC 7539 §2.8.2
    @Test
    fun chachaPolyMatchesRfc7539() {
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plain = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val sealed = Aead.seal(key, nonce, aad, plain)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.takeLast(16).toByteArray().toHex())
        assertContentEquals(plain, Aead.open(key, nonce, aad, sealed))
        assertNull(Aead.open(key, nonce, aad, sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }))
        assertNull(Aead.open(key, nonce, hex("00"), sealed))
    }

    @Test
    fun noncesHaveTheHapLayout() {
        assertEquals("000000005053 2d4d73673035".replace(" ", ""), Aead.labelNonce("PS-Msg05").toHex())
        assertEquals("000000000100000000000000", Aead.counterNonce(1).toHex())
    }

    @Test
    fun srpGroupIsA3072BitSafePrimeAndKMatchesTheFixedValue() {
        assertEquals(3072, SrpClient.N.bitLength())
        assertTrue(SrpClient.N.isProbablePrime(40))
        assertTrue(((SrpClient.N - BigInteger.ONE) / BigInteger.valueOf(2)).isProbablePrime(40))
        // aiohomekit hard-codes this k; ours is computed as H(N | PAD(g)), so agreement cross-checks N, g and the padding.
        assertEquals(
            "a9c2e2559bf0ebb53f0cbbf62282906bede7f2182f00678211fbd5bde5b285033a4993503b87397f9be5ec02080fedbc0835587ad039060879b8621e8c3659e0",
            SrpClient.K.toString(16),
        )
    }

    @Test
    fun srpClientAndAnIndependentServerAgree() {
        val rnd = Random(9)
        repeat(3) {
            val salt = rnd.nextBytes(16)
            val server = TestSrpServer("123-45-678", salt, rnd.nextBytes(32))
            val client = SrpClient("123-45-678", salt, server.publicKeyBytes, rnd.nextBytes(16))
            assertTrue(client.serverPublicValid)
            assertEquals(client.sessionKey.toHex(), server.sessionKey(client.publicKeyBytes).toHex())
            assertTrue(server.verifyClientProof(client.publicKeyBytes, client.clientProof))
            assertTrue(client.verifyServerProof(server.serverProof(client.publicKeyBytes, client.clientProof)))
        }
    }

    @Test
    fun aWrongSetupCodeFailsTheProof() {
        val salt = ByteArray(16) { it.toByte() }
        val server = TestSrpServer("123-45-678", salt, ByteArray(32) { 7 })
        val client = SrpClient("123-45-679", salt, server.publicKeyBytes, ByteArray(16) { 3 })
        assertFalse(server.verifyClientProof(client.publicKeyBytes, client.clientProof))
    }

    @Test
    fun aZeroServerKeyIsRejected() {
        assertFalse(SrpClient("123-45-678", ByteArray(16), ByteArray(384), ByteArray(16) { 1 }).serverPublicValid)
        assertFalse(SrpClient("123-45-678", ByteArray(16), SrpClient.unsigned(SrpClient.N), ByteArray(16) { 1 }).serverPublicValid)
    }
}

/** The accessory side of SRP-6a, written from the maths (v = g^x, B = kv + g^b, S = (A·v^u)^b), used by tests and the simulator. */
internal class TestSrpServer(setupCode: String, private val salt: ByteArray, privateKey: ByteArray) {
    private val b = BigInteger(1, privateKey)
    private val v = SrpClient.G.modPow(SrpClient.x(salt, setupCode), SrpClient.N)
    private val bPub = (SrpClient.K * v + SrpClient.G.modPow(b, SrpClient.N)).mod(SrpClient.N)
    val publicKeyBytes: ByteArray = SrpClient.unsigned(bPub)

    private fun s(a: ByteArray): BigInteger {
        val aPub = BigInteger(1, a)
        val u = BigInteger(1, SrpClient.sha512(SrpClient.padded(aPub), SrpClient.padded(bPub)))
        return (aPub * v.modPow(u, SrpClient.N)).mod(SrpClient.N).modPow(b, SrpClient.N)
    }
    fun sessionKey(a: ByteArray): ByteArray = SrpClient.sha512(SrpClient.padded(s(a)))
    private fun expectedProof(a: ByteArray): ByteArray {
        val hGroup = SrpClient.sha512(SrpClient.unsigned(SrpClient.N)).zip(SrpClient.sha512(SrpClient.unsigned(SrpClient.G))) { p, q -> (p.toInt() xor q.toInt()).toByte() }.toByteArray()
        return SrpClient.sha512(hGroup, SrpClient.sha512(SrpClient.USER.toByteArray()), salt, SrpClient.padded(BigInteger(1, a)), SrpClient.padded(bPub), sessionKey(a))
    }
    fun verifyClientProof(a: ByteArray, proof: ByteArray): Boolean = java.security.MessageDigest.isEqual(expectedProof(a), proof)
    fun serverProof(a: ByteArray, clientProof: ByteArray): ByteArray = SrpClient.sha512(SrpClient.padded(BigInteger(1, a)), clientProof, sessionKey(a))
}
