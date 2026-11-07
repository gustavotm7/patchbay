package dev.patchbay.core.net.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/**
 * A self-signed client certificate for devices that identify the client by one (Android TV Remote pairing). It is generated here,
 * kept only in the encrypted store, and used only for that device. The JDK has no public API for building certificates, so the
 * small DER structure is written by hand; tests parse it back with the platform's own certificate parser and verify its signature.
 */
public class ClientIdentity private constructor(public val certificate: X509Certificate, private val privateKey: PrivateKey) {

    public fun keyManagers(): Array<KeyManager> {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", privateKey, CharArray(0), arrayOf(certificate))
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }.keyManagers
    }

    /** Certificate and key as one byte string for the encrypted store: length-prefixed DER certificate, then the PKCS#8 key. */
    public fun toBytes(): ByteArray {
        val c = certificate.encoded
        val k = privateKey.encoded
        return byteArrayOf((c.size ushr 8).toByte(), c.size.toByte()) + c + k
    }

    public companion object {
        public fun fromBytes(b: ByteArray): ClientIdentity? = try {
            val n = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(b.copyOfRange(2, 2 + n).inputStream()) as X509Certificate
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(b.copyOfRange(2 + n, b.size)))
            ClientIdentity(cert, key)
        } catch (e: Exception) {
            null
        }

        /** RSA 2048, SHA-256, valid for ten years, common name [commonName]. */
        public fun generate(commonName: String = "Patchbay"): ClientIdentity {
            val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = ZonedDateTime.now(ZoneOffset.UTC)
            val utc = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
            val serial = ByteArray(8).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F).toByte() }
            val sigAlg = seq(oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), tlv(0x05, ByteArray(0)))
            val name = seq(tlv(0x31, seq(oid(byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0C, commonName.encodeToByteArray()))))
            val tbs = seq(
                tlv(0xA0, tlv(0x02, byteArrayOf(2))), // version 3
                tlv(0x02, serial), sigAlg, name,
                seq(tlv(0x17, now.minusMinutes(5).format(utc).encodeToByteArray()), tlv(0x17, now.plusYears(10).format(utc).encodeToByteArray())),
                name, pair.public.encoded,
            )
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }.sign()
            val der = seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            return ClientIdentity(cert, pair.private)
        }

        private fun oid(b: ByteArray) = tlv(0x06, b)
        private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, p -> a + p })
        private fun tlv(tag: Int, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = body.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                else -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xFF) }
            }
            out.write(body)
            return out.toByteArray()
        }
    }
}
