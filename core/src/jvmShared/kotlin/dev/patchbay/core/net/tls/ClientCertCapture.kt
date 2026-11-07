package dev.patchbay.core.net.tls

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * **Simulators only.** A server-side context that asks the client for a certificate and accepts whatever self-signed one it sends, so a
 * simulated Android TV can read the client's public key during pairing, as real TVs do. Patchbay itself never accepts an unpinned
 * *server*; app code does not use this. It lives here only because this package is the one place allowed to implement a trust
 * manager (policy rule TLS002).
 */
public object ClientCertCapture {
    public fun serverContext(keyManagers: Array<KeyManager>): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(AcceptAnyClient), null) }

    private object AcceptAnyClient : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit = throw CertificateException("not a client")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit = throw CertificateException("not a client")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit = throw CertificateException("not a client")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
