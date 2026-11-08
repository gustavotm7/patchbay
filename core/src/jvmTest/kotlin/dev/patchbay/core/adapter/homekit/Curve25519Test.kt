package dev.patchbay.core.adapter.homekit

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class Curve25519Test {
    // RFC 7748 §5.2
    @Test
    fun x25519Rfc7748Vectors() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            Curve25519.x25519(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")).toHex(),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            Curve25519.x25519(hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"), hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")).toHex(),
        )
    }

    // RFC 7748 §6.1 Diffie-Hellman
    @Test
    fun x25519DiffieHellman() {
        val a = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val b = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val aPub = Curve25519.x25519PublicKey(a); val bPub = Curve25519.x25519PublicKey(b)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", aPub.toHex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bPub.toHex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, Curve25519.x25519(a, bPub).toHex())
        assertEquals(shared, Curve25519.x25519(b, aPub).toHex())
    }

    // RFC 8032 §7.1
    @Test
    fun ed25519Rfc8032Vectors() {
        val vectors = listOf(
            Triple("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", "", "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            Triple("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb", "72", "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
            Triple("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7", "af82", "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
        )
        val pubs = listOf("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        for ((i, v) in vectors.take(2).withIndex()) {
            val seed = hex(v.first); val msg = hex(v.second)
            val pub = Curve25519.ed25519PublicKey(seed)
            assertEquals(pubs[i], pub.toHex())
            assertEquals(v.third, Curve25519.ed25519Sign(seed, msg).toHex())
            assertTrue(Curve25519.ed25519Verify(pub, msg, hex(v.third)))
        }
    }

    @Test
    fun tamperingIsRejected() {
        val seed = ByteArray(32) { it.toByte() }
        val pub = Curve25519.ed25519PublicKey(seed)
        val sig = Curve25519.ed25519Sign(seed, "hello".toByteArray())
        assertTrue(Curve25519.ed25519Verify(pub, "hello".toByteArray(), sig))
        assertFalse(Curve25519.ed25519Verify(pub, "hellp".toByteArray(), sig))
        for (i in sig.indices) assertFalse(Curve25519.ed25519Verify(pub, "hello".toByteArray(), sig.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        assertFalse(Curve25519.ed25519Verify(pub, "hello".toByteArray(), sig.copyOf(63)))
        assertFalse(Curve25519.ed25519Verify(ByteArray(32) { 0xff.toByte() }, "hello".toByteArray(), sig))
    }

    /** Independent check: the JDK's own Ed25519 and X25519 agree with ours on random inputs. */
    @Test
    fun agreesWithTheJdkOnRandomInputs() {
        val rnd = Random(7)
        repeat(20) {
            val seed = rnd.nextBytes(32); val msg = rnd.nextBytes(rnd.nextInt(0, 200))
            val pkcs8 = hex("302e020100300506032b657004220420") + seed
            val kf = KeyFactory.getInstance("Ed25519")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val jdkSig = Signature.getInstance("Ed25519").apply { initSign(priv); update(msg) }.sign()
            assertContentEquals(jdkSig, Curve25519.ed25519Sign(seed, msg))
            val jdkPub = kf.generatePublic(X509EncodedKeySpec(hex("302a300506032b6570032100") + Curve25519.ed25519PublicKey(seed)))
            assertTrue(Signature.getInstance("Ed25519").apply { initVerify(jdkPub); update(msg) }.verify(jdkSig))
        }
        repeat(20) {
            val a = KeyPairGenerator.getInstance("X25519").generateKeyPair()
            val sk = rnd.nextBytes(32)
            val ours = Curve25519.x25519PublicKey(sk)
            val jdkPriv = KeyFactory.getInstance("X25519").generatePrivate(PKCS8EncodedKeySpec(hex("302e020100300506032b656e04220420") + sk))
            val jdkPub = KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(hex("302a300506032b656e032100") + ours))
            val ka = javax.crypto.KeyAgreement.getInstance("X25519").apply { init(a.private); doPhase(jdkPub, true) }.generateSecret()
            val aPubRaw = a.public.encoded.takeLast(32).toByteArray()
            assertContentEquals(ka, Curve25519.x25519(sk, aPubRaw))
            assertTrue(jdkPriv.encoded.isNotEmpty())
        }
    }
}
