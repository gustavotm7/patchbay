package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import javax.crypto.AEADBadTagException

/**
 * Framing for Tuya protocol 3.4 (the 3.3 frame with an HMAC-SHA256 in place of the CRC) and 3.5 (a new `6699` frame sealed with AES-GCM).
 * 3.3 is in [TuyaProtocol]. Every frame from the device is untrusted: the prefix, length, suffix and the HMAC or GCM tag are checked before
 * anything inside is read, and lengths are bounded.
 *
 * 3.4: `00 00 55 AA | seq | cmd | length | [retcode (device to client)] payload | HMAC-SHA256 (32) | 00 00 AA 55`; length counts everything after
 * the 16-byte header; the HMAC covers the header and everything up to it.
 *
 * 3.5: `00 00 66 99 | 00 00 | seq | cmd | length | IV (12) | AES-GCM ciphertext | tag (16) | 00 00 99 66`; length counts the IV, the ciphertext and
 * the tag (not the suffix); the 14 header bytes after the prefix are the authenticated data. A device's plaintext starts with a 4-byte return code.
 */
internal object TuyaSecureFrames {
    const val HEADER_34: Int = 16
    const val HEADER_35: Int = 18
    private const val PREFIX_55AA = 0x000055AA
    private const val SUFFIX_55AA = 0x0000AA55
    private const val PREFIX_6699 = 0x00006699
    private const val SUFFIX_6699 = 0x00009966
    private const val HMAC = 32
    private const val TAG = 16
    private const val IV = 12

    /** A frame never carries more than a bounded payload plus the protocol's own overhead. */
    private const val MAX_BODY = TuyaProtocol.MAX_PAYLOAD + 128

    // ---- 3.4 ---------------------------------------------------------------------------------------

    /** [payload] is already encrypted. */
    fun encode34(seq: Int, cmd: Int, payload: ByteArray, key: ByteArray): ByteArray {
        require(payload.size <= MAX_BODY) { "payload too large" }
        val out = ByteArray(HEADER_34 + payload.size + HMAC + 4)
        putInt(out, 0, PREFIX_55AA); putInt(out, 4, seq); putInt(out, 8, cmd); putInt(out, 12, payload.size + HMAC + 4)
        payload.copyInto(out, HEADER_34)
        val at = HEADER_34 + payload.size
        TuyaCipher.hmacSha256(key, out.copyOfRange(0, at)).copyInto(out, at)
        putInt(out, at + HMAC, SUFFIX_55AA)
        return out
    }

    /** Validates the 16-byte header and returns how many more bytes to read. */
    fun remaining34(header: ByteArray): PatchbayResult<Int> {
        if (header.size != HEADER_34) return violation("short header")
        if (getInt(header, 0) != PREFIX_55AA) return violation("bad prefix")
        val n = getInt(header, 12)
        return if (n in (4 + HMAC + 4)..MAX_BODY) PatchbayResult.Ok(n) else violation("length out of range")
    }

    /** A device's frame: its payload is still encrypted. */
    fun decode34(frame: ByteArray, key: ByteArray): PatchbayResult<TuyaProtocol.Frame> {
        if (frame.size < HEADER_34 + 4 + HMAC + 4) return violation("short frame")
        if (getInt(frame, 0) != PREFIX_55AA) return violation("bad prefix")
        if (getInt(frame, 12) != frame.size - HEADER_34) return violation("length mismatch")
        if (getInt(frame, frame.size - 4) != SUFFIX_55AA) return violation("bad suffix")
        val macAt = frame.size - 4 - HMAC
        if (!TuyaCipher.same(frame.copyOfRange(macAt, macAt + HMAC), TuyaCipher.hmacSha256(key, frame.copyOfRange(0, macAt)))) return violation("bad checksum")
        return PatchbayResult.Ok(TuyaProtocol.Frame(getInt(frame, 4), getInt(frame, 8), getInt(frame, HEADER_34), frame.copyOfRange(HEADER_34 + 4, macAt)))
    }

    // ---- 3.5 ---------------------------------------------------------------------------------------

    /** [plain] is sealed here with a 12-byte [iv] that must never repeat under [key]. */
    fun encode35(seq: Int, cmd: Int, plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(plain.size <= MAX_BODY) { "payload too large" }
        val header = ByteArray(HEADER_35)
        putInt(header, 0, PREFIX_6699); putInt(header, 6, seq); putInt(header, 10, cmd); putInt(header, 14, IV + plain.size + TAG)
        val sealed = TuyaCipher.gcmSeal(key, iv, header.copyOfRange(4, HEADER_35), plain)
        val out = ByteArray(HEADER_35 + IV + sealed.size + 4)
        header.copyInto(out, 0); iv.copyInto(out, HEADER_35); sealed.copyInto(out, HEADER_35 + IV)
        putInt(out, out.size - 4, SUFFIX_6699)
        return out
    }

    /** Validates the 18-byte header and returns how many more bytes to read (the sealed body and the 4-byte suffix). */
    fun remaining35(header: ByteArray): PatchbayResult<Int> {
        if (header.size != HEADER_35) return violation("short header")
        if (getInt(header, 0) != PREFIX_6699) return violation("bad prefix")
        val n = getInt(header, 14)
        return if (n in (IV + 4 + TAG)..MAX_BODY) PatchbayResult.Ok(n + 4) else violation("length out of range")
    }

    /** A device's frame: the payload is opened here and the return code taken from its first four bytes. */
    fun decode35(frame: ByteArray, key: ByteArray): PatchbayResult<TuyaProtocol.Frame> {
        if (frame.size < HEADER_35 + IV + 4 + TAG + 4) return violation("short frame")
        if (getInt(frame, 0) != PREFIX_6699) return violation("bad prefix")
        if (getInt(frame, 14) != frame.size - HEADER_35 - 4) return violation("length mismatch")
        if (getInt(frame, frame.size - 4) != SUFFIX_6699) return violation("bad suffix")
        val plain = try {
            TuyaCipher.gcmOpen(
                key, frame.copyOfRange(HEADER_35, HEADER_35 + IV), frame.copyOfRange(4, HEADER_35), frame.copyOfRange(HEADER_35 + IV, frame.size - 4),
            )
        } catch (e: AEADBadTagException) {
            return violation("bad checksum")
        }
        if (plain.size < 4) return violation("no return code")
        return PatchbayResult.Ok(TuyaProtocol.Frame(getInt(frame, 6), getInt(frame, 10), getInt(plain, 0), plain.copyOfRange(4, plain.size)))
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun getInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun violation(why: String): PatchbayResult.Err =
        PatchbayResult.Err(PatchbayError.ProtocolViolation("tuya: $why. The device may use a different protocol version than the one Patchbay is speaking to it"))
}
