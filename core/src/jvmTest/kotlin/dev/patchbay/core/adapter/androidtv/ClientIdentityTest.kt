package dev.patchbay.core.adapter.androidtv

import dev.patchbay.core.net.tls.ClientIdentity
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientIdentityTest {
    @Test
    fun generatedCertificateIsASelfSignedRsa2048ThatThePlatformParsesAndVerifies() {
        val id = ClientIdentity.generate("Patchbay test")
        val cert = id.certificate
        cert.verify(cert.publicKey) // throws if the signature is wrong
        cert.checkValidity()
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(3, cert.version)
        assertTrue("CN=Patchbay test" in cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        val key = cert.publicKey as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
        assertEquals(65537.toBigInteger(), key.publicExponent)
    }

    @Test
    fun roundTripsThroughTheStoredBytesAndOffersAKeyManager() {
        val id = ClientIdentity.generate()
        val again = assertNotNull(ClientIdentity.fromBytes(id.toBytes()))
        assertEquals(id.certificate, again.certificate)
        assertTrue(again.keyManagers().isNotEmpty())
        assertNull(ClientIdentity.fromBytes(byteArrayOf(0, 5, 1, 2)))
        assertNull(ClientIdentity.fromBytes(ByteArray(0).let { byteArrayOf() }.let { byteArrayOf(1) }))
    }

    @Test
    fun twoIdentitiesAreDifferent() {
        assertTrue(ClientIdentity.generate().certificate != ClientIdentity.generate().certificate)
    }
}
