package dev.patchbay.simulators

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * A fake HomeKit thermostat (an ecobee-shaped accessory): HAP over TCP with pair-setup (SRP-6a), pair-verify and the
 * encrypted session, plus /accessories and /characteristics. Its cryptography is deliberately NOT the code the adapter
 * uses: key exchange, signatures and the AEAD come from the JDK providers, so agreement between the two is a real check.
 * Loopback only. Fault injection: [offline], [setupCodeAttemptsAllowed].
 */
public class HomeKitSimulator(
    public val address: String,
    public val setupCode: String = "314-15-926",
    public val accessoryId: String = "AA:BB:CC:11:22:33",
    port: Int = 0,
) : AutoCloseable {
    init { require(address.startsWith("127.")) { "simulators bind loopback only" } }

    @Volatile public var mode: Int = 1 // 0 off, 1 heat, 2 cool, 3 auto
    @Volatile public var targetTemp: Double = 21.0
    @Volatile public var heatThreshold: Double = 19.0
    @Volatile public var coolThreshold: Double = 25.0
    @Volatile public var currentTemp: Double = 19.5
    @Volatile public var humidity: Double = 41.0
    @Volatile public var bedroomOccupied: Boolean = false
    @Volatile public var offline: Boolean = false
    @Volatile public var setupCodeAttemptsAllowed: Int = 5

    /** Every write the accessory accepted, as "iid=value". */
    public val writes: MutableList<String> = CopyOnWriteArrayList()
    @Volatile public var unencryptedAccessoryRequests: Int = 0
    @Volatile public var pairVerifies: Int = 0

    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName(address), port)) }
    public val port: Int get() = server.localPort

    private val rnd = SecureRandom()
    private val ltKeys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    public val publicKey: ByteArray = ltKeys.public.encoded.takeLast(32).toByteArray()
    private val paired = ConcurrentHashMap<String, ByteArray>() // controller id -> LTPK
    private var wrongCodes = 0
    private val sockets = CopyOnWriteArrayList<Socket>()

    public val isPaired: Boolean get() = paired.isNotEmpty()
    public val pairedControllers: Set<String> get() = paired.keys

    init { thread(isDaemon = true, name = "hk-sim-$address") { acceptLoop() } }

    private fun acceptLoop() {
        while (true) {
            val s = try { server.accept() } catch (e: java.io.IOException) { return }
            if (offline) { s.close(); continue }
            sockets += s
            thread(isDaemon = true) { try { serve(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} } }
        }
    }

    override fun close() { try { server.close() } catch (_: Exception) {}; sockets.forEach { try { it.close() } catch (_: Exception) {} } }

    // ---- connection state ----
    private class Session(val inp: InputStream, val out: OutputStream) {
        var readKey: ByteArray? = null; var writeKey: ByteArray? = null
        var rc = 0L; var wc = 0L
        var buf = ByteArray(0); var pos = 0
        // pair-setup
        var srp: Srp? = null; var setupKey: ByteArray? = null
        // pair-verify
        var verifyShared: ByteArray? = null; var verifySession: ByteArray? = null; var verifyMyPub: ByteArray? = null; var verifyTheirPub: ByteArray? = null
        val secure: Boolean get() = readKey != null
    }

    private fun serve(socket: Socket) {
        val s = Session(socket.getInputStream(), socket.getOutputStream())
        while (true) {
            val req = readRequest(s) ?: return
            val (status, ctype, body) = route(s, req)
            val head = "HTTP/1.1 $status\r\nContent-Type: $ctype\r\nContent-Length: ${body.size}\r\n\r\n"
            writeOut(s, head.toByteArray() + body)
            if (pendingKeys?.first === s) activatePending()
        }
    }

    private class Req(val method: String, val path: String, val body: ByteArray)

    private fun rawByte(s: Session): Int = if (!s.secure) s.inp.read() else {
        if (s.pos >= s.buf.size) {
            val head = readN(s.inp, 2) ?: return -1
            val n = (head[0].toInt() and 0xff) or ((head[1].toInt() and 0xff) shl 8)
            val sealed = readN(s.inp, n + 16) ?: return -1
            s.buf = aead(Cipher.DECRYPT_MODE, s.readKey!!, nonce(s.rc++), head, sealed); s.pos = 0
        }
        s.buf[s.pos++].toInt() and 0xff
    }

    private fun readN(i: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n); var g = 0
        while (g < n) { val r = i.read(b, g, n - g); if (r < 0) return null; g += r }
        return b
    }

    private fun readRequest(s: Session): Req? {
        fun line(): String? {
            val sb = StringBuilder()
            while (true) { val c = rawByte(s); if (c < 0) return null; if (c == 10) return sb.toString().trimEnd('\r'); sb.append(c.toChar()) }
        }
        val first = line() ?: return null
        val parts = first.split(" ")
        var len = 0
        while (true) { val h = line() ?: return null; if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toInt() }
        val body = ByteArray(len)
        for (i in 0 until len) body[i] = (rawByte(s).takeIf { it >= 0 } ?: return null).toByte()
        return Req(parts[0], parts[1], body)
    }

    private fun writeOut(s: Session, data: ByteArray) {
        if (!s.secure) { s.out.write(data); s.out.flush(); return }
        var i = 0; val all = ByteArrayOutputStream()
        while (i < data.size) {
            val n = minOf(1024, data.size - i); val head = byteArrayOf(n.toByte(), (n shr 8).toByte())
            all.write(head); all.write(aead(Cipher.ENCRYPT_MODE, s.writeKey!!, nonce(s.wc++), head, data.copyOfRange(i, i + n))); i += n
        }
        s.out.write(all.toByteArray()); s.out.flush()
    }

    private fun nonce(counter: Long) = ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (counter ushr (8 * i)).toByte() }
    private fun aead(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").apply { init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce)); updateAAD(aad) }.doFinal(data)

    // ---- routes ----
    private data class Reply(val status: String, val type: String, val body: ByteArray)
    private fun tlv(vararg items: Pair<Int, ByteArray>) = Reply("200 OK", "application/pairing+tlv8", encodeTlv(items.toList()))
    private fun tlvError(state: Int, code: Int) = tlv(6 to byteArrayOf(state.toByte()), 7 to byteArrayOf(code.toByte()))

    private fun route(s: Session, r: Req): Reply = when {
        r.path == "/pair-setup" && r.method == "POST" -> pairSetup(s, r.body)
        r.path == "/pair-verify" && r.method == "POST" -> pairVerify(s, r.body)
        !s.secure -> { unencryptedAccessoryRequests++; Reply("470 Connection Authorization Required", "application/hap+json", """{"status":-70402}""".toByteArray()) }
        r.path == "/accessories" && r.method == "GET" -> Reply("200 OK", "application/hap+json", accessoriesJson().toByteArray())
        r.path.startsWith("/characteristics?id=") && r.method == "GET" -> Reply("200 OK", "application/hap+json", readJson(r.path.substringAfter("id=")).toByteArray())
        r.path == "/characteristics" && r.method == "PUT" -> put(String(r.body))
        r.path == "/pairings" && r.method == "POST" -> pairings(r.body)
        else -> Reply("404 Not Found", "application/hap+json", ByteArray(0))
    }

    private fun pairSetup(s: Session, body: ByteArray): Reply {
        val m = decodeTlv(body) ?: return Reply("400 Bad Request", "text/plain", ByteArray(0))
        when (m[6]?.firstOrNull()?.toInt()) {
            1 -> {
                if (isPaired) return tlvError(2, 6) // unavailable: already paired
                if (wrongCodes >= setupCodeAttemptsAllowed) return tlvError(2, 5)
                val salt = ByteArray(16).also(rnd::nextBytes)
                val srp = Srp(setupCode, salt, ByteArray(32).also(rnd::nextBytes)); s.srp = srp
                return tlv(6 to byteArrayOf(2), 3 to srp.bBytes, 2 to salt)
            }
            3 -> {
                val srp = s.srp ?: return tlvError(4, 1)
                val a = m[3] ?: return tlvError(4, 1); val proof = m[4] ?: return tlvError(4, 1)
                if (!srp.checkClient(a, proof)) { wrongCodes++; return tlvError(4, 2) }
                s.setupKey = srp.sessionKey(a)
                return tlv(6 to byteArrayOf(4), 4 to srp.serverProof(a, proof))
            }
            5 -> {
                val k = s.setupKey ?: return tlvError(6, 1)
                val enc = hkdf(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
                val plain = try { aead(Cipher.DECRYPT_MODE, enc, ByteArray(4) + "PS-Msg05".toByteArray(), ByteArray(0), m[5] ?: return tlvError(6, 1)) } catch (e: Exception) { return tlvError(6, 2) }
                val sub = decodeTlv(plain) ?: return tlvError(6, 1)
                val id = sub[1] ?: return tlvError(6, 1); val ltpk = sub[3] ?: return tlvError(6, 1); val sig = sub[10] ?: return tlvError(6, 1)
                val x = hkdf(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
                if (!verifyEd(ltpk, x + id + ltpk, sig)) return tlvError(6, 2)
                paired[String(id)] = ltpk
                val ax = hkdf(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
                val mySig = Signature.getInstance("Ed25519").apply { initSign(ltKeys.private); update(ax + accessoryId.toByteArray() + publicKey) }.sign()
                val inner = encodeTlv(listOf(1 to accessoryId.toByteArray(), 3 to publicKey, 10 to mySig))
                return tlv(6 to byteArrayOf(6), 5 to aead(Cipher.ENCRYPT_MODE, enc, ByteArray(4) + "PS-Msg06".toByteArray(), ByteArray(0), inner))
            }
        }
        return tlvError(2, 1)
    }

    private fun pairVerify(s: Session, body: ByteArray): Reply {
        val m = decodeTlv(body) ?: return Reply("400 Bad Request", "text/plain", ByteArray(0))
        when (m[6]?.firstOrNull()?.toInt()) {
            1 -> {
                val theirPub = m[3] ?: return tlvError(2, 1)
                val kp = KeyPairGenerator.getInstance("X25519").generateKeyPair()
                val myPub = kp.public.encoded.takeLast(32).toByteArray()
                val peer = KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(hexOf("302a300506032b656e032100") + theirPub))
                val shared = KeyAgreement.getInstance("X25519").apply { init(kp.private); doPhase(peer, true) }.generateSecret()
                val session = hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
                val sig = Signature.getInstance("Ed25519").apply { initSign(ltKeys.private); update(myPub + accessoryId.toByteArray() + theirPub) }.sign()
                val inner = encodeTlv(listOf(1 to accessoryId.toByteArray(), 10 to sig))
                s.verifyShared = shared; s.verifySession = session; s.verifyMyPub = myPub; s.verifyTheirPub = theirPub
                return tlv(6 to byteArrayOf(2), 3 to myPub, 5 to aead(Cipher.ENCRYPT_MODE, session, ByteArray(4) + "PV-Msg02".toByteArray(), ByteArray(0), inner))
            }
            3 -> {
                val session = s.verifySession ?: return tlvError(4, 1)
                val plain = try { aead(Cipher.DECRYPT_MODE, session, ByteArray(4) + "PV-Msg03".toByteArray(), ByteArray(0), m[5] ?: return tlvError(4, 1)) } catch (e: Exception) { return tlvError(4, 2) }
                val sub = decodeTlv(plain) ?: return tlvError(4, 1)
                val id = String(sub[1] ?: return tlvError(4, 1)); val sig = sub[10] ?: return tlvError(4, 1)
                val ltpk = paired[id] ?: return tlvError(4, 2)
                if (!verifyEd(ltpk, s.verifyTheirPub!! + id.toByteArray() + s.verifyMyPub!!, sig)) return tlvError(4, 2)
                pairVerifies++
                val reply = tlv(6 to byteArrayOf(4))
                // The reply to M3 is still plaintext; encryption starts with the next request.
                val shared = s.verifyShared!!
                pendingKeys = Triple(s, hkdf(shared, "Control-Salt", "Control-Write-Encryption-Key"), hkdf(shared, "Control-Salt", "Control-Read-Encryption-Key"))
                return reply
            }
        }
        return tlvError(2, 1)
    }

    @Volatile private var pendingKeys: Triple<Session, ByteArray, ByteArray>? = null

    private fun pairings(body: ByteArray): Reply {
        val m = decodeTlv(body) ?: return tlvError(2, 1)
        if (m[0]?.firstOrNull()?.toInt() == 4) { paired.remove(String(m[1] ?: return tlvError(2, 1))); return tlv(6 to byteArrayOf(2)) }
        return tlvError(2, 1)
    }

    private fun verifyEd(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = try {
        val key: PublicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(hexOf("302a300506032b6570032100") + pub))
        Signature.getInstance("Ed25519").apply { initVerify(key); update(msg) }.verify(sig)
    } catch (e: Exception) { false }

    // ---- accessory database ----
    private fun u(short: String) = "$short-0000-1000-8000-0026BB765291".let { "0000$it".takeLast(36) }
    private fun ch(iid: Int, type: String, fmt: String, perms: String, extra: String = "") = """{"iid":$iid,"type":"${u(type.padStart(8, '0'))}","format":"$fmt","perms":[$perms]$extra}"""

    private fun accessoriesJson(): String {
        val thermostat = listOf(
            ch(10, "0F", "uint8", "\"pr\",\"ev\"", ",\"value\":${currentState()},\"valid-values\":[0,1,2]"),
            ch(11, "33", "uint8", "\"pr\",\"pw\",\"ev\"", ",\"value\":$mode,\"valid-values\":[0,1,2,3]"),
            ch(12, "11", "float", "\"pr\",\"ev\"", ",\"value\":$currentTemp,\"minValue\":-50,\"maxValue\":100,\"minStep\":0.1"),
            ch(13, "35", "float", "\"pr\",\"pw\",\"ev\"", ",\"value\":$targetTemp,\"minValue\":9,\"maxValue\":32,\"minStep\":0.5"),
            ch(14, "36", "uint8", "\"pr\",\"pw\",\"ev\"", ",\"value\":0"),
            ch(15, "10", "float", "\"pr\",\"ev\"", ",\"value\":$humidity,\"minValue\":0,\"maxValue\":100"),
            ch(16, "0D", "float", "\"pr\",\"pw\",\"ev\"", ",\"value\":$coolThreshold,\"minValue\":18,\"maxValue\":35,\"minStep\":0.5"),
            ch(17, "12", "float", "\"pr\",\"pw\",\"ev\"", ",\"value\":$heatThreshold,\"minValue\":7,\"maxValue\":26,\"minStep\":0.5"),
            ch(18, "23", "string", "\"pr\"", ",\"value\":\"Hallway\""),
        ).joinToString(",")
        val info = listOf(ch(2, "23", "string", "\"pr\"", ",\"value\":\"Hallway\""), ch(3, "20", "string", "\"pr\"", ",\"value\":\"ecobee Inc.\"")).joinToString(",")
        val occ = listOf(ch(10, "71", "uint8", "\"pr\",\"ev\"", ",\"value\":${if (bedroomOccupied) 1 else 0}"), ch(11, "23", "string", "\"pr\"", ",\"value\":\"Bedroom\"")).joinToString(",")
        return """{"accessories":[{"aid":1,"services":[{"iid":1,"type":"${u("3E".padStart(8, '0'))}","characteristics":[$info]},{"iid":9,"type":"${u("4A".padStart(8, '0'))}","characteristics":[$thermostat]}]},""" +
            """{"aid":2,"services":[{"iid":9,"type":"${u("86".padStart(8, '0'))}","characteristics":[$occ]}]}]}"""
    }

    private fun currentState(): Int = when (mode) {
        1 -> if (currentTemp < targetTemp - 0.2) 1 else 0
        2 -> if (currentTemp > targetTemp + 0.2) 2 else 0
        3 -> if (currentTemp < heatThreshold) 1 else if (currentTemp > coolThreshold) 2 else 0
        else -> 0
    }

    private fun valueOf(aid: Int, iid: Int): String? = when {
        aid == 1 && iid == 10 -> "${currentState()}"
        aid == 1 && iid == 11 -> "$mode"
        aid == 1 && iid == 12 -> "$currentTemp"
        aid == 1 && iid == 13 -> "$targetTemp"
        aid == 1 && iid == 15 -> "$humidity"
        aid == 1 && iid == 16 -> "$coolThreshold"
        aid == 1 && iid == 17 -> "$heatThreshold"
        aid == 2 && iid == 10 -> if (bedroomOccupied) "1" else "0"
        else -> null
    }

    private fun readJson(ids: String): String = ids.split(",").joinToString(",", """{"characteristics":[""", "]}") { id ->
        val (a, i) = id.split(".").map { it.toInt() }
        valueOf(a, i)?.let { """{"aid":$a,"iid":$i,"value":$it}""" } ?: """{"aid":$a,"iid":$i,"status":-70409}"""
    }

    private fun put(body: String): Reply {
        val re = Regex(""""aid":(\d+),"iid":(\d+),"value":([-0-9.]+)""")
        for (m in re.findAll(body)) {
            val iid = m.groupValues[2].toInt(); val v = m.groupValues[3].toDouble()
            when (iid) {
                11 -> mode = v.toInt(); 13 -> targetTemp = v; 16 -> coolThreshold = v; 17 -> heatThreshold = v
                else -> return Reply("207 Multi-Status", "application/hap+json", """{"characteristics":[{"aid":1,"iid":$iid,"status":-70404}]}""".toByteArray())
            }
            writes += "$iid=${m.groupValues[3]}"
        }
        return Reply("204 No Content", "application/hap+json", ByteArray(0))
    }

    /** Applies the session keys once the M4 reply (plaintext) has been written. */
    private fun activatePending() {
        val p = pendingKeys ?: return
        pendingKeys = null
        p.first.readKey = p.second; p.first.writeKey = p.third; p.first.rc = 0; p.first.wc = 0
    }

    private fun hkdf(input: ByteArray, salt: String, info: String): ByteArray {
        fun hmac(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(k, "HmacSHA512")) }.doFinal(d)
        val prk = hmac(salt.toByteArray(), input)
        return hmac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(32)
    }

    private fun hexOf(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun encodeTlv(items: List<Pair<Int, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((t, v) in items) { var off = 0; do { val n = minOf(255, v.size - off); o.write(t); o.write(n); o.write(v, off, n); off += n } while (off < v.size) }
        return o.toByteArray()
    }

    private fun decodeTlv(b: ByteArray): Map<Int, ByteArray>? {
        val out = LinkedHashMap<Int, ByteArray>(); var i = 0
        while (i < b.size) {
            if (i + 2 > b.size) return null
            val t = b[i].toInt() and 0xff; val n = b[i + 1].toInt() and 0xff
            if (i + 2 + n > b.size) return null
            out[t] = (out[t] ?: ByteArray(0)) + b.copyOfRange(i + 2, i + 2 + n); i += 2 + n
        }
        return out
    }

    /** SRP-6a server, written from the maths: v = g^x, B = k·v + g^b, S = (A·v^u)^b. */
    private class Srp(code: String, private val salt: ByteArray, privateKey: ByteArray) {
        private val b = BigInteger(1, privateKey)
        private val v = G.modPow(x(salt, code), N)
        private val bPub = (K * v + G.modPow(b, N)).mod(N)
        val bBytes: ByteArray = un(bPub)
        private fun s(a: ByteArray): BigInteger {
            val aPub = BigInteger(1, a)
            val u = BigInteger(1, h(pad(aPub), pad(bPub)))
            return (aPub * v.modPow(u, N)).mod(N).modPow(b, N)
        }
        fun sessionKey(a: ByteArray): ByteArray = h(pad(s(a)))
        private fun m1(a: ByteArray): ByteArray {
            val hg = h(un(N)).zip(h(un(G))) { p, q -> (p.toInt() xor q.toInt()).toByte() }.toByteArray()
            return h(hg, h("Pair-Setup".toByteArray()), salt, pad(BigInteger(1, a)), pad(bPub), sessionKey(a))
        }
        fun checkClient(a: ByteArray, proof: ByteArray): Boolean {
            if (BigInteger(1, a).mod(N).signum() == 0) return false
            return MessageDigest.isEqual(m1(a), proof)
        }
        fun serverProof(a: ByteArray, clientProof: ByteArray): ByteArray = h(pad(BigInteger(1, a)), clientProof, sessionKey(a))

        companion object {
            val N = BigInteger(
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
            fun h(vararg p: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").also { md -> p.forEach(md::update) }.digest()
            fun un(v: BigInteger): ByteArray = v.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            fun pad(v: BigInteger): ByteArray = un(v).let { ByteArray(384 - it.size) + it }
            val K = BigInteger(1, h(un(N), pad(G)))
            fun x(salt: ByteArray, code: String) = BigInteger(1, h(salt, h("Pair-Setup:$code".toByteArray())))
        }
    }
}
