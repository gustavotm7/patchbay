package dev.patchbay.simulators

import dev.patchbay.core.net.tls.ClientCertCapture
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * A fake Android TV / Google TV for the Android TV Remote v2 protocol: a pairing port and a remote port over TLS (self-signed server
 * certificate, any client certificate accepted at the handshake), with its own independent protobuf and hash code so a mistake in the
 * adapter isn't mirrored here. Pairing: it builds the code the way a TV does (first code byte = first digest byte), shows it in
 * [displayedCode], and checks the secret. The remote port only serves clients that paired. Binds loopback only.
 * Fault injection: [offline], [badLength].
 */
public class AndroidTvSimulator(
    public val address: String,
    keyStore: KeyStore,
    keyPassword: CharArray,
    pairingPort: Int = 0,
    remotePort: Int = 0,
    private val pingMs: Long = 150,
) : AutoCloseable {
    init { require(address.startsWith("127.")) { "simulators bind loopback only" } }

    @Volatile public var offline: Boolean = false
    @Volatile public var badLength: Boolean = false
    @Volatile public var displayedCode: String? = null
    @Volatile public var pongs: Int = 0

    /** Key presses received: key code and direction. */
    public val keys: MutableList<Pair<Int, Int>> = CopyOnWriteArrayList()
    private val pairedClients = CopyOnWriteArrayList<BigInteger>() // client RSA moduli that completed pairing
    private val sockets = CopyOnWriteArrayList<Socket>()

    private val bind = InetAddress.getByName(address)
    private val pairing: SSLServerSocket
    private val remote: SSLServerSocket
    public val pairingPort: Int get() = pairing.localPort
    public val remotePort: Int get() = remote.localPort
    private val serverKey: RSAPublicKey

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, keyPassword) }
        val ctx = ClientCertCapture.serverContext(kmf.keyManagers)
        pairing = (ctx.serverSocketFactory.createServerSocket(pairingPort, 8, bind) as SSLServerSocket).apply { wantClientAuth = true }
        remote = (ctx.serverSocketFactory.createServerSocket(remotePort, 8, bind) as SSLServerSocket).apply { wantClientAuth = true }
        serverKey = (keyStore.getCertificate(keyStore.aliases().nextElement()) as X509Certificate).publicKey as RSAPublicKey
        thread(isDaemon = true, name = "atv-sim-pairing") { acceptLoop(pairing, ::servePairing) }
        thread(isDaemon = true, name = "atv-sim-remote") { acceptLoop(remote, ::serveRemote) }
    }

    private fun acceptLoop(s: SSLServerSocket, handler: (SSLSocket) -> Unit) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() as SSLSocket }.getOrNull() ?: break
            sockets += c
            thread(isDaemon = true) { runCatching { if (!offline) handler(c) }; runCatching { c.close() } }
        }
    }

    // ---- independent protobuf ----

    private fun varint(v0: Long): ByteArray { var v = v0; val o = ByteArrayOutputStream(); while (v >= 0x80) { o.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()); return o.toByteArray() }
    private fun field(num: Int, payload: ByteArray) = varint(((num shl 3) or 2).toLong()) + varint(payload.size.toLong()) + payload
    private fun intField(num: Int, v: Long) = varint((num shl 3).toLong()) + varint(v)
    private fun framed(body: ByteArray) = varint(body.size.toLong()) + body

    private fun readFrame(inp: InputStream): ByteArray {
        var len = 0; var shift = 0
        while (true) { val b = inp.read(); if (b < 0) throw java.io.EOFException(); len = len or ((b and 0x7F) shl shift); if (b and 0x80 == 0) break; shift += 7 }
        val out = ByteArray(len); var off = 0
        while (off < len) { val r = inp.read(out, off, len - off); if (r < 0) throw java.io.EOFException(); off += r }
        return out
    }

    /** Field numbers present in a message, with their varint values or raw bytes. */
    private class Msg(val ints: Map<Int, Long>, val blobs: Map<Int, ByteArray>)
    private fun decode(b: ByteArray): Msg {
        val ints = HashMap<Int, Long>(); val blobs = HashMap<Int, ByteArray>(); var i = 0
        fun v(): Long { var r = 0L; var sh = 0; while (true) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl sh); if (x < 0x80) return r; sh += 7 } }
        while (i < b.size) {
            val tag = v(); val f = (tag ushr 3).toInt()
            if ((tag and 7) == 0L) ints[f] = v() else { val n = v().toInt(); blobs[f] = b.copyOfRange(i, i + n); i += n }
        }
        return Msg(ints, blobs)
    }

    private fun outer(status: Int, vararg parts: ByteArray) = intField(1, 2) + intField(2, status.toLong()) + parts.fold(ByteArray(0)) { a, p -> a + p }

    // ---- pairing port ----

    private fun modBytes(n: BigInteger) = n.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
    private fun expBytes(e: BigInteger) = e.toString(16).uppercase().let { if (it.length % 2 == 1) "0$it" else it }.let { h -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }

    private fun servePairing(c: SSLSocket) {
        c.soTimeout = 30_000
        val inp = c.getInputStream(); val out: OutputStream = c.getOutputStream()
        val clientKey = (c.session.peerCertificates.firstOrNull() as? X509Certificate)?.publicKey as? RSAPublicKey ?: return
        var expected: ByteArray? = null
        while (true) {
            val m = decode(readFrame(inp))
            when {
                10 in m.blobs -> out.write(framed(outer(200, field(11, field(1, "Sim TV".toByteArray())))))
                20 in m.blobs -> out.write(framed(outer(200, field(20, field(1, intField(1, 3) + intField(2, 6)) + intField(3, 2)))))
                30 in m.blobs -> {
                    // Build the code like a TV: pick two random bytes, hash, and make the first code byte the first digest byte.
                    val tail = ByteArray(2).also { SecureRandom().nextBytes(it) }
                    val digest = MessageDigest.getInstance("SHA-256").digest(
                        modBytes(clientKey.modulus) + expBytes(clientKey.publicExponent) + modBytes(serverKey.modulus) + expBytes(serverKey.publicExponent) + tail,
                    )
                    expected = digest
                    displayedCode = "%02X%02X%02X".format(digest[0].toInt() and 0xFF, tail[0].toInt() and 0xFF, tail[1].toInt() and 0xFF)
                    out.write(framed(outer(200, field(31, ByteArray(0)))))
                }
                40 in m.blobs -> {
                    val secret = decode(m.blobs.getValue(40)).blobs[1]
                    if (secret != null && expected != null && secret.contentEquals(expected)) {
                        pairedClients += clientKey.modulus
                        out.write(framed(outer(200, field(41, field(1, secret)))))
                    } else {
                        out.write(framed(outer(402, field(41, ByteArray(0)))))
                    }
                    out.flush(); return
                }
            }
            out.flush()
        }
    }

    // ---- remote port ----

    private fun serveRemote(c: SSLSocket) {
        val clientKey = (c.session.peerCertificates.firstOrNull() as? X509Certificate)?.publicKey as? RSAPublicKey ?: return
        if (clientKey.modulus !in pairedClients) return // a TV only serves clients that paired
        val out = c.getOutputStream(); val inp = c.getInputStream()
        if (badLength) { out.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)); out.flush(); return }
        out.write(framed(field(1, intField(1, 611)))); out.flush()
        c.soTimeout = pingMs.toInt()
        var started = false; var n = 0
        while (!c.isClosed) {
            try {
                val m = decode(readFrame(inp))
                when {
                    1 in m.blobs -> { out.write(framed(field(2, intField(1, 611)))); out.write(framed(field(40, intField(1, 1)))); started = true }
                    9 in m.blobs -> pongs++
                    10 in m.blobs -> decode(m.blobs.getValue(10)).let { keys += (it.ints[1] ?: 0L).toInt() to (it.ints[2] ?: 0L).toInt() }
                }
                out.flush()
            } catch (e: java.net.SocketTimeoutException) {
                if (started) { out.write(framed(field(8, intField(1, (++n).toLong()) + intField(2, 1)))); out.flush() }
            }
        }
    }

    /** The TV forgets every paired client, like removing the remote in its settings. */
    public fun forgetClients() { pairedClients.clear() }

    override fun close() {
        runCatching { pairing.close() }; runCatching { remote.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}
