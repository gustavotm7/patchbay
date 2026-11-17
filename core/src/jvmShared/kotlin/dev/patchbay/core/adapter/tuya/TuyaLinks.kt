package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/** The three versions of the Tuya local protocol Patchbay speaks. Which one a device uses depends on its firmware. */
internal enum class TuyaVersion(val label: String) {
    V33("3.3"), V34("3.4"), V35("3.5");

    companion object {
        fun byLabel(label: String?): TuyaVersion? = entries.firstOrNull { it.label == label }
    }
}

/** What a device answered: its return code (0 is success) and, when there was one, the decrypted JSON. */
internal class TuyaReply(val retcode: Int, val plain: ByteArray)

/**
 * One connection to a device, in one protocol version. [query] asks for the data points and [control] writes some; each returns the device's reply
 * after decrypting it. Blocking (the socket's read timeout bounds it); the adapter runs it off the main thread.
 */
internal abstract class TuyaLink {
    /** Asks for every data point. Empty acknowledgements that some devices send before the data are skipped (a few, then the last one is returned). */
    abstract fun query(json: ByteArray): PatchbayResult<TuyaReply>

    /**
     * Asks for the data points named in [json] (`"dps":{"1":null,...}`) with the control-style command. "Device22" devices, which answer [query] with "data unvalid",
     * only answer this way. Protocol 3.3 only; the newer versions answer [PatchbayError.Unsupported].
     */
    open fun queryDps(json: ByteArray): PatchbayResult<TuyaReply> =
        PatchbayResult.Err(PatchbayError.Unsupported("this protocol version has no query by data point number"))

    abstract fun control(json: ByteArray): PatchbayResult<TuyaReply>
}

internal object TuyaLinks {
    /**
     * Opens a link on a connection that is already established. For 3.4 and 3.5 this first agrees a session key with the device (three messages), which
     * also proves each side holds the local key: a device with another key never completes it.
     */
    fun open(
        version: TuyaVersion, key: ByteArray, input: InputStream, output: OutputStream, nextSeq: AtomicInteger, random: SecureRandom,
    ): PatchbayResult<TuyaLink> = when (version) {
        TuyaVersion.V33 -> PatchbayResult.Ok(V33Link(key, input, output, nextSeq))
        TuyaVersion.V34, TuyaVersion.V35 -> SecureLink(version, key, input, output, nextSeq, random).let { link ->
            when (val r = link.negotiate()) {
                is PatchbayResult.Ok -> PatchbayResult.Ok(link)
                is PatchbayResult.Err -> r
            }
        }
    }

    /**
     * The key that protects a connection after the session handshake: the two 16-byte nonces XORed together, then run through AES under the local key
     * (3.4: one ECB block; 3.5: AES-GCM with the first 12 bytes of our nonce as the IV, keeping the first 16 bytes of ciphertext).
     */
    fun sessionKey(version: TuyaVersion, localKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray {
        require(localNonce.size == 16 && remoteNonce.size == 16) { "nonces are 16 bytes" }
        val mixed = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        return if (version == TuyaVersion.V34) TuyaCipher.encryptBlocks(localKey, mixed)
        else TuyaCipher.gcmSeal(localKey, localNonce.copyOfRange(0, 12), ByteArray(0), mixed).copyOfRange(0, 16)
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r < 0) throw IOException("closed")
            off += r
        }
        return out
    }

    private fun wrongKey() = PatchbayResult.Err(PatchbayError.Unauthorized("the device didn't accept the local key"))

    /**
     * Decrypts a reply's payload with [open]; an empty one stays empty. A refusal (non-zero return code) is decrypted too when it can be, because some devices put the
     * reason ("data unvalid") in it, but an error payload that is not encrypted is simply left unread.
     */
    private fun reply(retcode: Int?, payload: ByteArray, open: (ByteArray) -> ByteArray): PatchbayResult<TuyaReply> {
        val code = retcode ?: 0
        if (payload.isEmpty()) return PatchbayResult.Ok(TuyaReply(code, ByteArray(0)))
        val plain = try {
            open(payload)
        } catch (e: BadPaddingException) {
            return if (code != 0) PatchbayResult.Ok(TuyaReply(code, ByteArray(0))) else wrongKey()
        } catch (e: IllegalBlockSizeException) {
            return if (code != 0) PatchbayResult.Ok(TuyaReply(code, ByteArray(0)))
            else PatchbayResult.Err(PatchbayError.ProtocolViolation("tuya: reply wasn't a protocol message Patchbay understands"))
        }
        return PatchbayResult.Ok(TuyaReply(code, TuyaProtocol.stripVersionHeader(plain)))
    }

    /** A few empty acknowledgements before the data are normal for some devices; more than this is not, and the last frame is returned as it is. */
    private const val MAX_EMPTY_REPLIES = 3

    /** Protocol 3.3: one frame each way, AES-ECB under the local key, CRC-32. */
    private class V33Link(private val key: ByteArray, private val input: InputStream, private val output: OutputStream, private val seq: AtomicInteger) : TuyaLink() {
        override fun query(json: ByteArray): PatchbayResult<TuyaReply> =
            exchange(TuyaProtocol.CMD_DP_QUERY, TuyaCipher.encrypt(key, json), wantData = true)

        override fun queryDps(json: ByteArray): PatchbayResult<TuyaReply> =
            exchange(TuyaProtocol.CMD_CONTROL_NEW, TuyaProtocol.VERSION_HEADER + TuyaCipher.encrypt(key, json), wantData = true)

        override fun control(json: ByteArray): PatchbayResult<TuyaReply> =
            exchange(TuyaProtocol.CMD_CONTROL, TuyaProtocol.VERSION_HEADER + TuyaCipher.encrypt(key, json), wantData = false)

        private fun exchange(cmd: Int, payload: ByteArray, wantData: Boolean): PatchbayResult<TuyaReply> {
            output.write(TuyaProtocol.encode(seq.getAndIncrement(), cmd, payload)); output.flush()
            var empty = 0
            while (true) {
                val r = readReply()
                val reply = when (r) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
                if (!wantData || reply.retcode != 0 || reply.plain.isNotEmpty() || ++empty > MAX_EMPTY_REPLIES) return r
            }
        }

        private fun readReply(): PatchbayResult<TuyaReply> {
            val header = input.readExactly(TuyaProtocol.HEADER_BYTES)
            val remaining = when (val n = TuyaProtocol.remainingAfterHeader(header)) {
                is PatchbayResult.Ok -> n.value
                is PatchbayResult.Err -> return n
            }
            val frame = when (val f = TuyaProtocol.decode(header + input.readExactly(remaining), fromDevice = true)) {
                is PatchbayResult.Ok -> f.value
                is PatchbayResult.Err -> return f
            }
            return reply(frame.retcode, TuyaProtocol.stripVersionHeader(frame.payload)) { TuyaCipher.decrypt(key, it) }
        }
    }

    /** Protocols 3.4 and 3.5: a session key agreed first, then frames protected by it (3.4: ECB and HMAC-SHA256, 3.5: AES-GCM). */
    private class SecureLink(
        private val version: TuyaVersion, private val localKey: ByteArray, private val input: InputStream, private val output: OutputStream,
        private val seq: AtomicInteger, private val random: SecureRandom,
    ) : TuyaLink() {
        private var key: ByteArray = localKey

        fun negotiate(): PatchbayResult<Unit> {
            val localNonce = ByteArray(16).also(random::nextBytes)
            send(TuyaProtocol.CMD_SESSION_START, localNonce)
            val answer = when (val r = receive()) {
                is PatchbayResult.Ok -> r.value
                is PatchbayResult.Err -> return r
            }
            if (answer.retcode != 0) return wrongKey()
            val body = answer.plain
            if (body.size < 48) return PatchbayResult.Err(PatchbayError.ProtocolViolation("tuya: the session answer was too short"))
            val remoteNonce = body.copyOfRange(0, 16)
            // The device proves it holds the local key by returning an HMAC of our nonce.
            if (!TuyaCipher.same(body.copyOfRange(16, 48), TuyaCipher.hmacSha256(localKey, localNonce))) return wrongKey()
            send(TuyaProtocol.CMD_SESSION_FINISH, TuyaCipher.hmacSha256(localKey, remoteNonce))
            key = sessionKey(version, localKey, localNonce, remoteNonce)
            return PatchbayResult.Ok(Unit)
        }

        override fun query(json: ByteArray): PatchbayResult<TuyaReply> = exchange(TuyaProtocol.CMD_DP_QUERY_NEW, json, wantData = true)

        override fun control(json: ByteArray): PatchbayResult<TuyaReply> = exchange(TuyaProtocol.CMD_CONTROL_NEW, TuyaProtocol.versionHeader(version.label) + json, wantData = false)

        private fun exchange(cmd: Int, plain: ByteArray, wantData: Boolean): PatchbayResult<TuyaReply> {
            send(cmd, plain)
            var empty = 0
            while (true) {
                val r = receive()
                val reply = when (r) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
                if (!wantData || reply.retcode != 0 || reply.plain.isNotEmpty() || ++empty > MAX_EMPTY_REPLIES) return r
            }
        }

        private fun send(cmd: Int, plain: ByteArray) {
            val frame = when (version) {
                TuyaVersion.V34 -> TuyaSecureFrames.encode34(seq.getAndIncrement(), cmd, TuyaCipher.encrypt(key, plain), key)
                else -> TuyaSecureFrames.encode35(seq.getAndIncrement(), cmd, plain, key, ByteArray(12).also(random::nextBytes))
            }
            output.write(frame); output.flush()
        }

        private fun receive(): PatchbayResult<TuyaReply> {
            val frame = when (version) {
                TuyaVersion.V34 -> {
                    val header = input.readExactly(TuyaSecureFrames.HEADER_34)
                    val rest = when (val n = TuyaSecureFrames.remaining34(header)) {
                        is PatchbayResult.Ok -> n.value
                        is PatchbayResult.Err -> return n
                    }
                    TuyaSecureFrames.decode34(header + input.readExactly(rest), key)
                }
                else -> {
                    val header = input.readExactly(TuyaSecureFrames.HEADER_35)
                    val rest = when (val n = TuyaSecureFrames.remaining35(header)) {
                        is PatchbayResult.Ok -> n.value
                        is PatchbayResult.Err -> return n
                    }
                    TuyaSecureFrames.decode35(header + input.readExactly(rest), key)
                }
            }
            val f = when (frame) {
                is PatchbayResult.Ok -> frame.value
                is PatchbayResult.Err -> return frame
            }
            // 3.5 frames arrive opened already; a 3.4 payload is decrypted under the key the frame was checked with.
            return if (version == TuyaVersion.V34) reply(f.retcode, f.payload) { TuyaCipher.decrypt(key, it) }
            else PatchbayResult.Ok(TuyaReply(f.retcode ?: 0, if (f.retcode == 0) TuyaProtocol.stripVersionHeader(f.payload) else ByteArray(0)))
        }
    }
}
