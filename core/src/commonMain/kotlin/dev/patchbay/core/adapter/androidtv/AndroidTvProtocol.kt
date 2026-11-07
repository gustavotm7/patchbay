package dev.patchbay.core.adapter.androidtv

import dev.patchbay.core.model.RemoteKey
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult

/**
 * Android TV Remote v2 (Google TV, Android TV, Chromecast with Google TV): protobuf messages behind a varint length prefix, on
 * TLS 6467 (pairing) and 6466 (remote). Field numbers and values are taken from the reference projects louis49/androidtv-remote
 * (MIT) and tronikos/androidtvremote2 (Apache-2.0); see docs/protocols/cast.md. Only the messages Patchbay needs are written, with
 * a tiny protobuf reader/writer (varints and length-delimited fields). Everything from the TV is untrusted and bounded.
 */
public object AndroidTvProtocol {
    public const val PAIRING_PORT: Int = 6467
    public const val REMOTE_PORT: Int = 6466
    public const val MAX_MESSAGE_BYTES: Int = 16_384

    /** Feature bits from the remote's `configure`: ping, key, power, volume (the ones Patchbay uses). */
    public const val FEATURES: Int = 1 or 2 or 32 or 64

    // ---- tiny protobuf ------------------------------------------------------------------------------

    private class Writer {
        val out = ArrayList<Byte>()
        fun varint(v0: Long) { var v = v0; while (v >= 0x80) { out += ((v and 0x7F) or 0x80).toByte(); v = v ushr 7 }; out += v.toByte() }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun int(field: Int, v: Int) { tag(field, 0); varint(v.toLong() and 0xFFFFFFFFL) }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); b.forEach { out += it } }
        fun string(field: Int, s: String) = bytes(field, s.encodeToByteArray())
        fun message(field: Int, build: Writer.() -> Unit) = bytes(field, Writer().apply(build).out.toByteArray())
        fun done(): ByteArray = out.toByteArray()
    }

    /** Parsed fields of one message: varints by field number, and length-delimited by field number. */
    public class Fields(public val ints: Map<Int, Long>, public val blobs: Map<Int, ByteArray>)

    private fun parse(b: ByteArray): PatchbayResult<Fields> {
        if (b.size > MAX_MESSAGE_BYTES) return violation("message too large")
        val ints = HashMap<Int, Long>(); val blobs = HashMap<Int, ByteArray>()
        var i = 0
        fun varint(): Long? {
            var r = 0L; var shift = 0
            while (i < b.size && shift <= 63) { val x = b[i++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl shift); if (x and 0x80 == 0) return r; shift += 7 }
            return null
        }
        while (i < b.size) {
            val tag = varint() ?: return violation("bad tag")
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> ints[field] = varint() ?: return violation("bad varint")
                2 -> {
                    val len = varint() ?: return violation("bad length")
                    if (len < 0 || len > b.size - i) return violation("field length")
                    blobs[field] = b.copyOfRange(i, i + len.toInt()); i += len.toInt()
                }
                1 -> { if (i + 8 > b.size) return violation("truncated"); i += 8 }
                5 -> { if (i + 4 > b.size) return violation("truncated"); i += 4 }
                else -> return violation("unsupported wire type")
            }
        }
        return PatchbayResult.Ok(Fields(ints, blobs))
    }

    // ---- framing ------------------------------------------------------------------------------------

    /** The varint length prefix followed by [body]. */
    public fun frame(body: ByteArray): ByteArray {
        require(body.size <= MAX_MESSAGE_BYTES) { "message too large" }
        val w = Writer(); w.varint(body.size.toLong())
        return w.done() + body
    }

    // ---- pairing (port 6467) ------------------------------------------------------------------------

    private const val STATUS_OK = 200
    private const val PROTOCOL_VERSION = 2
    private const val ENCODING_HEX = 3
    private const val SYMBOL_LENGTH = 6
    private const val ROLE_INPUT = 1

    private fun outer(build: Writer.() -> Unit): ByteArray = Writer().apply { int(1, PROTOCOL_VERSION); int(2, STATUS_OK); build() }.done()

    public fun pairingRequest(clientName: String): ByteArray = outer { message(10) { string(1, "atvremote"); string(2, clientName) } }

    /** The input encoding Patchbay offers: a 6-character hexadecimal code typed on this device. */
    public fun pairingOptions(): ByteArray = outer { message(20) { message(1) { int(1, ENCODING_HEX); int(2, SYMBOL_LENGTH) }; int(3, ROLE_INPUT) } }

    public fun pairingConfiguration(): ByteArray = outer { message(30) { message(1) { int(1, ENCODING_HEX); int(2, SYMBOL_LENGTH) }; int(2, ROLE_INPUT) } }

    public fun pairingSecretMessage(secret: ByteArray): ByteArray = outer { message(40) { bytes(1, secret) } }

    public enum class PairingStep { REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK }

    /** Reads a pairing reply: which step it is, or an error for any non-OK status. */
    public fun parsePairing(body: ByteArray): PatchbayResult<PairingStep> {
        val f = when (val r = parse(body)) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
        val status = f.ints[2]?.toInt() ?: return violation("no status")
        if (status != STATUS_OK) return PatchbayResult.Err(PatchbayError.Unauthorized(if (status == 402) "the TV rejected the code" else "the TV refused pairing (status $status)"))
        return when {
            11 in f.blobs -> PatchbayResult.Ok(PairingStep.REQUEST_ACK)
            20 in f.blobs -> PatchbayResult.Ok(PairingStep.OPTIONS)
            31 in f.blobs -> PatchbayResult.Ok(PairingStep.CONFIGURATION_ACK)
            41 in f.blobs -> PatchbayResult.Ok(PairingStep.SECRET_ACK)
            else -> violation("unexpected pairing message")
        }
    }

    /**
     * The pairing secret: SHA-256 over the client's RSA modulus and exponent, the TV's modulus and exponent, and the last two
     * bytes of the 6-character code. The first byte of the digest must equal the first byte of the code (a check the TV built in).
     * [sha256] is injected so this stays platform-free. Returns null when the code is malformed or fails the check.
     */
    public fun pairingSecret(clientModulus: ByteArray, clientExponent: ByteArray, serverModulus: ByteArray, serverExponent: ByteArray, code: String, sha256: (ByteArray) -> ByteArray): ByteArray? {
        val c = code.trim().uppercase()
        if (c.length != 6 || !c.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val bytes = ByteArray(3) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val digest = sha256(clientModulus + clientExponent + serverModulus + serverExponent + bytes.copyOfRange(1, 3))
        return if (digest.size == 32 && digest[0] == bytes[0]) digest else null
    }

    // ---- remote (port 6466) -------------------------------------------------------------------------

    private fun deviceInfo(): Writer.() -> Unit = { int(3, 1); string(4, "1"); string(5, "atvremote"); string(6, "1.0.0") }

    /** Our reply to the TV's `configure`: the features we use that the TV supports, and who we are. */
    public fun remoteConfigure(serverFeatures: Int): ByteArray = Writer().apply { message(1) { int(1, serverFeatures and FEATURES); message(2, deviceInfo()) } }.done()

    public fun remoteSetActive(active: Int): ByteArray = Writer().apply { message(2) { int(1, active) } }.done()

    public fun remotePingResponse(val1: Int): ByteArray = Writer().apply { message(9) { int(1, val1) } }.done()

    /** A short press of [key]. */
    public fun remoteKey(key: RemoteKey): ByteArray = Writer().apply { message(10) { int(1, keyCode(key)); int(2, 3) } }.done()

    public fun keyCode(key: RemoteKey): Int = when (key) {
        RemoteKey.UP -> 19; RemoteKey.DOWN -> 20; RemoteKey.LEFT -> 21; RemoteKey.RIGHT -> 22; RemoteKey.SELECT -> 23
        RemoteKey.HOME -> 3; RemoteKey.BACK -> 4; RemoteKey.PLAY_PAUSE -> 85
        RemoteKey.VOLUME_UP -> 24; RemoteKey.VOLUME_DOWN -> 25; RemoteKey.MUTE -> 164; RemoteKey.POWER -> 26
    }

    public sealed interface Incoming {
        public data class Configure(val features: Int) : Incoming
        public data class SetActive(val active: Int) : Incoming
        public data class Ping(val val1: Int) : Incoming
        public data class Started(val on: Boolean) : Incoming
        public data object Error : Incoming
        public data object Other : Incoming
    }

    public fun parseRemote(body: ByteArray): PatchbayResult<Incoming> {
        val f = when (val r = parse(body)) { is PatchbayResult.Ok -> r.value; is PatchbayResult.Err -> return r }
        fun inner(field: Int): Fields? = f.blobs[field]?.let { (parse(it) as? PatchbayResult.Ok)?.value }
        return PatchbayResult.Ok(
            when {
                1 in f.blobs -> Incoming.Configure(inner(1)?.ints?.get(1)?.toInt() ?: 0)
                2 in f.blobs -> Incoming.SetActive(inner(2)?.ints?.get(1)?.toInt() ?: 0)
                3 in f.blobs -> Incoming.Error
                8 in f.blobs -> Incoming.Ping(inner(8)?.ints?.get(1)?.toInt() ?: 0)
                40 in f.blobs -> Incoming.Started((inner(40)?.ints?.get(1) ?: 0L) != 0L)
                else -> Incoming.Other
            },
        )
    }

    private fun violation(why: String): PatchbayResult.Err = PatchbayResult.Err(PatchbayError.ProtocolViolation("androidtv: $why"))
}
