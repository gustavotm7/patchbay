package dev.patchbay.core.adapter.rtsp

/**
 * H.264 over RTP (RFC 6184) as cameras send it on an interleaved RTSP connection: single NAL units, STAP-A aggregates and
 * FU-A fragments. Output is Annex-B (start code + NAL), which is what a decoder reads from a pipe. Everything is untrusted:
 * malformed packets are dropped, never thrown on, and a NAL is capped at [MAX_NAL] bytes.
 */
public class H264Depacketizer {
    private var fragment: ByteArray? = null
    private var fragmentLen = 0
    private var expectedSeq = -1

    /** Packets dropped as malformed, out of order or oversize (diagnostics only). */
    public var dropped: Long = 0
        private set

    /** Feeds one RTP packet; returns the complete NAL units it finished, each prefixed with a start code. */
    public fun push(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): List<ByteArray> {
        if (offset < 0 || length < 0 || offset + length > packet.size) { dropped++; return emptyList() }
        if (length < 13) { dropped++; return emptyList() }
        val b0 = packet[offset].toInt() and 0xff
        if (b0 ushr 6 != 2) { dropped++; return emptyList() }
        val padding = b0 and 0x20 != 0
        val extension = b0 and 0x10 != 0
        val csrc = b0 and 0x0f
        val seq = ((packet[offset + 2].toInt() and 0xff) shl 8) or (packet[offset + 3].toInt() and 0xff)
        var start = offset + 12 + csrc * 4
        var end = offset + length
        if (extension) {
            if (start + 4 > end) { dropped++; return emptyList() }
            start += 4 + 4 * (((packet[start + 2].toInt() and 0xff) shl 8) or (packet[start + 3].toInt() and 0xff))
        }
        if (padding) {
            val pad = packet[end - 1].toInt() and 0xff
            if (pad == 0 || pad > end - start) { dropped++; return emptyList() }
            end -= pad
        }
        if (start >= end) { dropped++; return emptyList() }

        // A gap in sequence numbers breaks any fragment in progress.
        if (expectedSeq >= 0 && seq != expectedSeq && fragment != null) { fragment = null; dropped++ }
        expectedSeq = (seq + 1) and 0xffff

        val type = packet[start].toInt() and 0x1f
        return when {
            type in 1..23 -> listOf(annexB(packet, start, end - start))
            type == 24 -> stap(packet, start + 1, end)
            type == 28 -> fu(packet, start, end)
            else -> { dropped++; emptyList() } // STAP-B, MTAP, FU-B, reserved: cameras don't use them
        }
    }

    private fun stap(p: ByteArray, from: Int, end: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        var i = from
        while (i + 2 <= end) {
            val size = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
            i += 2
            if (size == 0 || i + size > end) { dropped++; break }
            out += annexB(p, i, size)
            i += size
        }
        return out
    }

    private fun fu(p: ByteArray, start: Int, end: Int): List<ByteArray> {
        if (end - start < 3) { dropped++; return emptyList() }
        val indicator = p[start].toInt() and 0xff
        val header = p[start + 1].toInt() and 0xff
        val first = header and 0x80 != 0
        val last = header and 0x40 != 0
        val data = start + 2
        if (first) {
            fragment = ByteArray(1024).also { it[0] = 0; it[1] = 0; it[2] = 0; it[3] = 1; it[4] = ((indicator and 0xe0) or (header and 0x1f)).toByte() }
            fragmentLen = 5
        }
        var buf = fragment ?: run { dropped++; return emptyList() }
        val n = end - data
        if (fragmentLen + n > MAX_NAL) { fragment = null; dropped++; return emptyList() }
        if (fragmentLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, fragmentLen + n)).also { fragment = it }
        p.copyInto(buf, fragmentLen, data, end)
        fragmentLen += n
        if (!last) return emptyList()
        val done = buf.copyOf(fragmentLen)
        fragment = null
        return listOf(done)
    }

    private fun annexB(p: ByteArray, from: Int, n: Int): ByteArray {
        val out = ByteArray(4 + n)
        out[3] = 1
        p.copyInto(out, 4, from, from + n)
        return out
    }

    public companion object {
        public const val MAX_NAL: Int = 2 * 1024 * 1024
        public val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)
    }
}

/** What a camera's SDP says about its H.264 video, reduced to what playing it needs. */
public data class SdpVideo(val control: String, val payloadType: Int, val parameterSets: List<ByteArray>) {
    /** SPS/PPS as Annex-B, to send to the decoder before the first frame (cameras repeat them in-band too, but not always at the start). */
    public fun parameterSetsAnnexB(): ByteArray = parameterSets.fold(ByteArray(0)) { acc, ps -> acc + H264Depacketizer.START_CODE + ps }

    public companion object {
        private const val MAX_SDP = 16 * 1024

        /** Finds the first H.264 video media section. The control URL is kept only if it is relative or `*`: an absolute one from the network is never followed. */
        public fun parse(sdp: String): SdpVideo? {
            if (sdp.length > MAX_SDP) return null
            val lines = sdp.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            var i = lines.indexOfFirst { it.startsWith("m=video ") }
            if (i < 0) return null
            val header = lines[i].split(' ')
            val pt = header.getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..127 } ?: return null
            var control: String? = null
            var isH264 = false
            var sets: List<ByteArray> = emptyList()
            i++
            while (i < lines.size && !lines[i].startsWith("m=")) {
                val l = lines[i]
                when {
                    l.startsWith("a=control:") -> control = l.removePrefix("a=control:").trim()
                    l.startsWith("a=rtpmap:$pt ") -> isH264 = l.substringAfter(' ').uppercase().startsWith("H264/")
                    l.startsWith("a=fmtp:$pt ") -> {
                        val v = l.substringAfter("sprop-parameter-sets=", "").substringBefore(';').trim()
                        if (v.isNotEmpty()) sets = v.split(',').take(4).mapNotNull { decodeBase64(it.trim()) }.filter { it.isNotEmpty() && it.size <= 1024 }
                    }
                }
                i++
            }
            if (!isH264) return null
            val c = control ?: "*"
            val safe = if (c == "*" || c.none { it == ':' || it == ' ' || it.isISOControl() } && !c.startsWith("/") && !c.contains("..")) c else return null
            return SdpVideo(safe, pt, sets)
        }

        private fun decodeBase64(s: String): ByteArray? = try {
            java.util.Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Splits a byte stream of concatenated JPEGs (ffmpeg's `image2pipe` MJPEG) into frames, by SOI/EOI markers. Frames over [MAX_FRAME] are discarded. */
public class MjpegSplitter(private val onFrame: (ByteArray) -> Unit) {
    private var buf = ByteArray(64 * 1024)
    private var len = 0

    public fun push(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        for (k in offset until offset + length) {
            val b = data[k]
            if (len == 0) {
                // Wait for the start-of-image marker FF D8.
                if (b == 0xFF.toByte()) { buf[0] = b; len = 1 }
                continue
            }
            if (len == 1) {
                if (b == 0xD8.toByte()) { buf[1] = b; len = 2 } else len = if (b == 0xFF.toByte()) 1 else 0
                continue
            }
            if (len >= MAX_FRAME) { len = 0; continue }
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = b
            // JPEG data inside the scan never contains FF D9 except as the end marker (FF is stuffed as FF 00).
            if (b == 0xD9.toByte() && buf[len - 2] == 0xFF.toByte() && len > 4) {
                onFrame(buf.copyOf(len))
                len = 0
            }
        }
    }

    public companion object {
        public const val MAX_FRAME: Int = 8 * 1024 * 1024
    }
}
