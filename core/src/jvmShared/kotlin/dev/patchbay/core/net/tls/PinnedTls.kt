package dev.patchbay.core.net.tls

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Device trust (ADR 0005): trust-on-first-use with SHA-256 SubjectPublicKeyInfo pinning.
 *
 * This is the **only** place an X509TrustManager may exist (CI rule TLS002). Platform trust stores are
 * never consulted for devices, so a user-installed CA can't intercept device traffic.
 * There's no hostname verification: we connect by IP and device certificates carry meaningless or
 * vendor-wide names, and the pin is stronger. Certificate validity dates are ignored for the same reason.
 */
public object PinnedTls {

    /** Hex SHA-256 of the leaf certificate's SubjectPublicKeyInfo (DER). */
    public fun spkiSha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }

    /** "3f9a 8c2e 1b7d …" in 8 groups of 8 hex chars, for display at pairing time (mono font). */
    public fun display(hex: String): String = hex.chunked(8).joinToString(" ")

    /**
     * Creates a context that accepts exactly the key whose SPKI hash is [pinnedSha256], or, when it's
     * null (first use), any key, recording what it saw in [observed] so pairing can show it to the user.
     */
    public fun context(pinnedSha256: String?, observed: ObservedKey): SSLContext {
        val tm = PinningTrustManager(pinnedSha256, observed)
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }

    /** Restricts a socket to TLS 1.2+ (some devices, like webOS TVs, don't do 1.3). */
    public fun harden(socket: SSLSocket) {
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        // Deliberately no endpoint identification: see class KDoc. Never set a HostnameVerifier.
    }

    /** What the server presented during the handshake. */
    public class ObservedKey {
        @Volatile public var spkiSha256: String? = null
            internal set
    }

    /** Thrown when a pinned device presents a different key. Surfaced as PatchbayError.TrustChanged. */
    public class PinMismatch(public val expected: String, public val actual: String) : CertificateException("pinned key changed")

    private class PinningTrustManager(private val pin: String?, private val observed: ObservedKey) : X509ExtendedTrustManager() {

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate presented")
            val actual = spkiSha256(leaf)
            observed.spkiSha256 = actual
            if (pin != null && !MessageDigest.isEqual(pin.toByteArray(), actual.toByteArray())) {
                throw PinMismatch(pin, actual)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

        // We are always the client; refuse to act as a server trust manager.
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit = throw CertificateException("client auth not supported")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit = throw CertificateException("client auth not supported")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit = throw CertificateException("client auth not supported")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
