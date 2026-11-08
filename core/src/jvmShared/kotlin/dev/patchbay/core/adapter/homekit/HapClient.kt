package dev.patchbay.core.adapter.homekit

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/** Anything that goes wrong talking HAP. [kind] tells the caller what to do about it. */
internal class HapException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { IO, PROTOCOL, WRONG_CODE, ALREADY_PAIRED, BUSY, TOO_MANY_TRIES, UNAUTHENTICATED, UNTRUSTED_ACCESSORY, REJECTED }
}

/** What a paired accessory lets us do again later: our identity, our long-term key and the accessory's. Secret at rest. */
internal class HapPairingKeys(val controllerId: String, val controllerSeed: ByteArray, val accessoryId: String, val accessoryPublicKey: ByteArray, val port: Int = 0) {
    fun withPort(p: Int) = HapPairingKeys(controllerId, controllerSeed, accessoryId, accessoryPublicKey, p)

    fun encode(): ByteArray = Tlv8.encode(
        listOf(1 to controllerId.toByteArray(), 2 to accessoryId.toByteArray(), 3 to accessoryPublicKey, 4 to controllerSeed) +
            if (port in 1..65_535) listOf(5 to byteArrayOf((port shr 8).toByte(), port.toByte())) else emptyList(),
    )

    companion object {
        fun decode(bytes: ByteArray): HapPairingKeys? {
            val m = Tlv8.map(bytes) ?: return null
            val cid = m[1]?.toString(Charsets.UTF_8) ?: return null
            val aid = m[2]?.toString(Charsets.UTF_8) ?: return null
            val pk = m[3]?.takeIf { it.size == 32 } ?: return null
            val seed = m[4]?.takeIf { it.size == 32 } ?: return null
            val port = m[5]?.takeIf { it.size == 2 }?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }?.takeIf { it in 1..65_535 } ?: 0
            return HapPairingKeys(cid, seed, aid, pk, port)
        }
    }
}

internal class HapResponse(val status: Int, val body: ByteArray)

/** Frames HAP's encrypted session: 2-byte little-endian length (also the AAD), ciphertext, 16-byte tag; one counter per direction. */
internal class SecureStreams(private val raw: InputStream, private val rawOut: OutputStream, private val writeKey: ByteArray, private val readKey: ByteArray) {
    private var sent = 0L
    private var received = 0L
    private var pending = ByteArray(0)
    private var pendingPos = 0

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pendingPos >= pending.size && !fill()) return -1
            val n = minOf(len, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, b, off, n)
            pendingPos += n
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) { buf.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }
        override fun flush() {
            val all = buf.toByteArray(); buf.reset()
            var i = 0
            val frames = ByteArrayOutputStream()
            while (i < all.size) {
                val n = minOf(MAX_FRAME, all.size - i)
                val len = byteArrayOf(n.toByte(), (n shr 8).toByte())
                frames.write(len)
                frames.write(Aead.seal(writeKey, Aead.counterNonce(sent++), len, all.copyOfRange(i, i + n)))
                i += n
            }
            rawOut.write(frames.toByteArray()); rawOut.flush()
        }
    }

    private fun fill(): Boolean {
        val head = ByteArray(2)
        if (!readFully(head, true)) return false
        val n = (head[0].toInt() and 0xff) or ((head[1].toInt() and 0xff) shl 8)
        if (n > MAX_FRAME) throw HapException(HapException.Kind.PROTOCOL, "oversize frame")
        val sealed = ByteArray(n + Aead.TAG)
        if (!readFully(sealed, false)) throw HapException(HapException.Kind.IO, "connection closed mid-frame")
        pending = Aead.open(readKey, Aead.counterNonce(received++), head, sealed) ?: throw HapException(HapException.Kind.PROTOCOL, "frame failed authentication")
        pendingPos = 0
        return true
    }

    private fun readFully(buf: ByteArray, eofOk: Boolean): Boolean {
        var got = 0
        while (got < buf.size) {
            val r = raw.read(buf, got, buf.size - got)
            if (r < 0) { if (eofOk && got == 0) return false; throw HapException(HapException.Kind.IO, "connection closed") }
            got += r
        }
        return true
    }

    companion object { const val MAX_FRAME = 1024 }
}

/**
 * One HAP-over-IP connection: plain HTTP for pairing, then (after pair-verify) the same HTTP inside encrypted frames.
 * Blocking; callers run it on an IO dispatcher and serialise access. Everything the accessory sends is size-bounded and strictly parsed.
 */
internal class HapConnection(private val socket: Socket, private val host: String) : Closeable {
    private var input: InputStream = socket.getInputStream()
    private var output: OutputStream = socket.getOutputStream()
    @Volatile var secure: Boolean = false
        private set

    fun request(method: String, path: String, contentType: String? = null, body: ByteArray = ByteArray(0), accept: String? = null): HapResponse {
        val head = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\n")
            if (accept != null) append("Accept: $accept\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            if (body.isNotEmpty() || method != "GET") append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(head); if (body.isNotEmpty()) output.write(body); output.flush()
        while (true) {
            val r = readResponse() ?: continue // skips unsolicited EVENT/1.0 messages
            return r
        }
    }

    private fun readResponse(): HapResponse? {
        val status = readLine()
        val isEvent = status.startsWith("EVENT/1.0")
        if (!isEvent && !status.startsWith("HTTP/1.1 ")) throw HapException(HapException.Kind.PROTOCOL, "unexpected reply")
        var length = 0
        var headers = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            if (++headers > 32) throw HapException(HapException.Kind.PROTOCOL, "too many headers")
            val i = line.indexOf(':')
            if (i < 0) throw HapException(HapException.Kind.PROTOCOL, "bad header")
            val name = line.substring(0, i).trim().lowercase()
            val value = line.substring(i + 1).trim()
            when (name) {
                "content-length" -> length = value.toIntOrNull()?.takeIf { it in 0..MAX_BODY } ?: throw HapException(HapException.Kind.PROTOCOL, "bad content length")
                "transfer-encoding" -> throw HapException(HapException.Kind.PROTOCOL, "chunked replies are not part of HAP")
            }
        }
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val r = input.read(body, got, length - got)
            if (r < 0) throw HapException(HapException.Kind.IO, "connection closed")
            got += r
        }
        if (isEvent) return null
        val code = status.substring(9).take(3).toIntOrNull() ?: throw HapException(HapException.Kind.PROTOCOL, "bad status")
        return HapResponse(code, body)
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw HapException(HapException.Kind.IO, "connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > MAX_LINE) throw HapException(HapException.Kind.PROTOCOL, "header line too long")
        }
        return sb.toString()
    }

    internal fun switchToEncrypted(writeKey: ByteArray, readKey: ByteArray) {
        val s = SecureStreams(input, output, writeKey, readKey)
        input = s.input; output = s.output; secure = true
    }

    override fun close() { try { socket.close() } catch (_: IOException) {} }

    companion object {
        const val MAX_BODY = 1 shl 20
        const val MAX_LINE = 4096
        const val TLV = "application/pairing+tlv8"
        const val JSON = "application/hap+json"
    }
}

/** The controller side of HAP pairing and session setup. Pure protocol: bring your own connection. */
internal object HapPairing {
    private val random = SecureRandom()

    private fun hkdf(key: ByteArray, salt: String, info: String) = Hkdf.sha512(key, salt.toByteArray(), info.toByteArray())

    private fun tlvExchange(c: HapConnection, path: String, items: List<Pair<Int, ByteArray>>, expectState: Int): Map<Int, ByteArray> {
        val resp = c.request("POST", path, HapConnection.TLV, Tlv8.encode(items))
        val m = Tlv8.map(resp.body) ?: throw HapException(HapException.Kind.PROTOCOL, "malformed TLV")
        m[Tlv8.ERROR]?.let { throw errorFor(it.firstOrNull()?.toInt()) }
        if (resp.status != 200) throw HapException(HapException.Kind.REJECTED, "accessory answered HTTP ${resp.status}")
        if (m[Tlv8.STATE]?.singleOrNull()?.toInt() != expectState) throw HapException(HapException.Kind.PROTOCOL, "unexpected pairing state")
        return m
    }

    private fun errorFor(code: Int?): HapException = when (code) {
        2 -> HapException(HapException.Kind.WRONG_CODE, "the setup code was not accepted")
        3 -> HapException(HapException.Kind.BUSY, "the accessory asked us to wait before trying again")
        4 -> HapException(HapException.Kind.ALREADY_PAIRED, "the accessory cannot take another pairing")
        5 -> HapException(HapException.Kind.TOO_MANY_TRIES, "too many wrong codes; the accessory has locked pairing for now")
        6 -> HapException(HapException.Kind.ALREADY_PAIRED, "the accessory is already paired to another controller")
        7 -> HapException(HapException.Kind.BUSY, "the accessory is busy with another pairing")
        else -> HapException(HapException.Kind.REJECTED, "the accessory refused (error ${code ?: "?"})")
    }

    /** Pair-setup M1..M6. [setupCode] is "XXX-XX-XXX". Returns the keys to store. */
    fun setup(c: HapConnection, setupCode: String, srpPrivate: ByteArray = ByteArray(16).also(random::nextBytes), controllerSeed: ByteArray = ByteArray(32).also(random::nextBytes)): HapPairingKeys {
        val m2 = tlvExchange(c, "/pair-setup", listOf(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(0)), 2)
        val salt = m2[Tlv8.SALT]?.takeIf { it.size == 16 } ?: throw HapException(HapException.Kind.PROTOCOL, "bad salt")
        val serverPub = m2[Tlv8.PUBLIC_KEY]?.takeIf { it.size in 1..SrpClient.LEN } ?: throw HapException(HapException.Kind.PROTOCOL, "bad public key")
        val srp = SrpClient(setupCode, salt, serverPub, srpPrivate)
        if (!srp.serverPublicValid) throw HapException(HapException.Kind.PROTOCOL, "unsafe SRP value")

        val m4 = tlvExchange(c, "/pair-setup", listOf(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKeyBytes, Tlv8.PROOF to srp.clientProof), 4)
        val serverProof = m4[Tlv8.PROOF] ?: throw HapException(HapException.Kind.PROTOCOL, "no accessory proof")
        if (!srp.verifyServerProof(serverProof)) throw HapException(HapException.Kind.UNTRUSTED_ACCESSORY, "the accessory did not prove it knows the setup code")

        val k = srp.sessionKey
        val controllerId = UUID.nameUUIDFromBytes(controllerSeed).toString().uppercase()
        val ltpk = Curve25519.ed25519PublicKey(controllerSeed)
        val x = hkdf(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val signature = Curve25519.ed25519Sign(controllerSeed, x + controllerId.toByteArray() + ltpk)
        val inner = Tlv8.encode(listOf(Tlv8.IDENTIFIER to controllerId.toByteArray(), Tlv8.PUBLIC_KEY to ltpk, Tlv8.SIGNATURE to signature))
        val encKey = hkdf(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val m5 = Tlv8.encode(listOf(Tlv8.STATE to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to Aead.seal(encKey, Aead.labelNonce("PS-Msg05"), ByteArray(0), inner)))

        val resp = c.request("POST", "/pair-setup", HapConnection.TLV, m5)
        val m6 = Tlv8.map(resp.body) ?: throw HapException(HapException.Kind.PROTOCOL, "malformed TLV")
        m6[Tlv8.ERROR]?.let { throw errorFor(it.firstOrNull()?.toInt()) }
        if (resp.status != 200 || m6[Tlv8.STATE]?.singleOrNull()?.toInt() != 6) throw HapException(HapException.Kind.PROTOCOL, "unexpected pairing state")
        val sealed = m6[Tlv8.ENCRYPTED_DATA] ?: throw HapException(HapException.Kind.PROTOCOL, "no encrypted data")
        val plain = Aead.open(encKey, Aead.labelNonce("PS-Msg06"), ByteArray(0), sealed) ?: throw HapException(HapException.Kind.UNTRUSTED_ACCESSORY, "accessory message failed authentication")
        val sub = Tlv8.map(plain) ?: throw HapException(HapException.Kind.PROTOCOL, "malformed TLV")
        val accId = sub[Tlv8.IDENTIFIER] ?: throw HapException(HapException.Kind.PROTOCOL, "no accessory id")
        val accKey = sub[Tlv8.PUBLIC_KEY]?.takeIf { it.size == 32 } ?: throw HapException(HapException.Kind.PROTOCOL, "bad accessory key")
        val accSig = sub[Tlv8.SIGNATURE] ?: throw HapException(HapException.Kind.PROTOCOL, "no accessory signature")
        val accX = hkdf(k, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        if (!Curve25519.ed25519Verify(accKey, accX + accId + accKey, accSig)) throw HapException(HapException.Kind.UNTRUSTED_ACCESSORY, "accessory signature is wrong")
        return HapPairingKeys(controllerId, controllerSeed, accId.toString(Charsets.UTF_8), accKey)
    }

    /** Pair-verify M1..M4; on success the connection switches to encrypted frames. */
    fun verify(c: HapConnection, keys: HapPairingKeys, ephemeral: ByteArray = ByteArray(32).also(random::nextBytes)) {
        val myPub = Curve25519.x25519PublicKey(ephemeral)
        val m2 = tlvExchange(c, "/pair-verify", listOf(Tlv8.STATE to byteArrayOf(1), Tlv8.PUBLIC_KEY to myPub), 2)
        val accPub = m2[Tlv8.PUBLIC_KEY]?.takeIf { it.size == 32 } ?: throw HapException(HapException.Kind.PROTOCOL, "bad public key")
        val sealed = m2[Tlv8.ENCRYPTED_DATA] ?: throw HapException(HapException.Kind.PROTOCOL, "no encrypted data")
        val shared = Curve25519.x25519(ephemeral, accPub)
        if (shared.all { it == 0.toByte() }) throw HapException(HapException.Kind.UNTRUSTED_ACCESSORY, "degenerate key exchange")
        val sessionKey = hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val plain = Aead.open(sessionKey, Aead.labelNonce("PV-Msg02"), ByteArray(0), sealed) ?: throw HapException(HapException.Kind.UNTRUSTED_ACCESSORY, "accessory message failed authentication")
        val sub = Tlv8.map(plain) ?: throw HapException(HapException.Kind.PROTOCOL, "malformed TLV")
        val id = sub[Tlv8.IDENTIFIER] ?: throw HapException(HapException.Kind.PROTOCOL, "no accessory id")
        val sig = sub[Tlv8.SIGNATURE] ?: throw HapException(HapException.Kind.PROTOCOL, "no signature")
        if (id.toString(Charsets.UTF_8) != keys.accessoryId) throw HapException(HapException.Kind.UNTRUSTED_ACCESSORY, "a different accessory answered at this address")
        if (!Curve25519.ed25519Verify(keys.accessoryPublicKey, accPub + id + myPub, sig)) throw HapException(HapException.Kind.UNTRUSTED_ACCESSORY, "accessory key does not match the one pinned at pairing")

        val mySig = Curve25519.ed25519Sign(keys.controllerSeed, myPub + keys.controllerId.toByteArray() + accPub)
        val inner = Tlv8.encode(listOf(Tlv8.IDENTIFIER to keys.controllerId.toByteArray(), Tlv8.SIGNATURE to mySig))
        tlvExchange(
            c, "/pair-verify",
            listOf(Tlv8.STATE to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to Aead.seal(sessionKey, Aead.labelNonce("PV-Msg03"), ByteArray(0), inner)), 4,
        )
        c.switchToEncrypted(
            writeKey = hkdf(shared, "Control-Salt", "Control-Write-Encryption-Key"),
            readKey = hkdf(shared, "Control-Salt", "Control-Read-Encryption-Key"),
        )
    }

    /** Removes our pairing from the accessory (over a verified session). */
    fun remove(c: HapConnection, keys: HapPairingKeys) {
        tlvExchange(c, "/pairings", listOf(Tlv8.STATE to byteArrayOf(1), Tlv8.METHOD to byteArrayOf(4), Tlv8.IDENTIFIER to keys.controllerId.toByteArray()), 2)
    }
}
