package dev.patchbay.core.net

import dev.patchbay.core.net.tls.PinnedTls
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** TOFU pinning against a loopback TLS server with throwaway self-signed certificates. */
class PinnedTlsTest {
    private val guard = NetGuard()
    private val servers = mutableListOf<SSLServerSocket>()

    private fun keystore(name: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            // Tests run with the module directory as the working directory.
            java.io.File("src/jvmTest/resources/tls/$name").inputStream().use { load(it, "testpass".toCharArray()) }
        }

    private fun fingerprint(name: String): String = PinnedTls.spkiSha256(keystore(name).getCertificate("device") as X509Certificate)

    /** Echo-one-byte TLS server presenting [name]'s certificate. */
    private fun server(name: String): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore(name), "testpass".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { c.use { it.getOutputStream().write(7); it.getOutputStream().flush(); it.getInputStream().read() } } }
            }
        }
        return ss.localPort
    }

    @AfterTest
    fun close() = servers.forEach { it.close() }

    private val lo get() = guard.address("127.0.0.1").getOrNull()!!

    @Test
    fun firstUseReturnsTheFingerprintToConfirm(): Unit = runBlocking {
        val port = server("device-a.p12")
        val conn = guard.connectTls(lo, port, pinnedSha256 = null, deviceName = "TV").getOrNull()
        assertNotNull(conn)
        conn.use {
            assertTrue(it.firstUse)
            assertEquals(fingerprint("device-a.p12"), it.spkiSha256)
            assertEquals(7, it.socket.getInputStream().read())
        }
    }

    @Test
    fun matchingPinConnects(): Unit = runBlocking {
        val port = server("device-a.p12")
        val conn = guard.connectTls(lo, port, fingerprint("device-a.p12"), "TV").getOrNull()
        assertNotNull(conn)
        conn.use { assertEquals(false, it.firstUse) }
    }

    @Test
    fun changedKeyIsTrustChangedNeverSilentlyAccepted(): Unit = runBlocking {
        val port = server("device-b.p12")
        val err = guard.connectTls(lo, port, fingerprint("device-a.p12"), "TV").errorOrNull()
        assertIs<PatchbayError.TrustChanged>(err)
        assertEquals("TV", err.deviceName)
    }

    @Test
    fun displayGroupsHex() {
        assertEquals("01234567 89abcdef", PinnedTls.display("0123456789abcdef"))
    }
}
