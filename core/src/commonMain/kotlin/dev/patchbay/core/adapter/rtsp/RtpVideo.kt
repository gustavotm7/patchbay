package dev.patchbay.core.adapter.rtsp

/** The video codecs Patchbay can play from a camera's RTSP stream. */
public enum class VideoCodec(public val label: String) { H264("H.264"), H265("H.265") }

/** Where the payload sits in one RTP packet (RFC 3550 §5.1), or null when the header is malformed. Shared by both depacketizers so there is one audited copy. */
internal class RtpPayload(val start: Int, val end: Int, val seq: Int) {
    companion object {
        fun locate(packet: ByteArray, offset: Int, length: Int): RtpPayload? {
            if (offset < 0 || length < 13 || offset + length > packet.size) return null
            val b0 = packet[offset].toInt() and 0xff
            if (b0 ushr 6 != 2) return null
            val padding = b0 and 0x20 != 0
            val extension = b0 and 0x10 != 0
            val csrc = b0 and 0x0f
            val seq = ((packet[offset + 2].toInt() and 0xff) shl 8) or (packet[offset + 3].toInt() and 0xff)
            var start = offset + 12 + csrc * 4
            var end = offset + length
            if (extension) {
                if (start + 4 > end) return null
                start += 4 + 4 * (((packet[start + 2].toInt() and 0xff) shl 8) or (packet[start + 3].toInt() and 0xff))
            }
            if (padding) {
                val pad = packet[end - 1].toInt() and 0xff
                if (pad == 0 || pad > end - start) return null
                end -= pad
            }
            if (start >= end) return null
            return RtpPayload(start, end, seq)
        }
    }
}

/**
 * Turns RTP packets of one video stream into Annex-B NAL units (start code + NAL), which is what a decoder reads from a pipe. Everything a camera
 * sends is untrusted: malformed packets are dropped, never thrown on, a fragment broken by a lost packet is discarded, and a NAL is capped at [MAX_NAL] bytes.
 */
public abstract class VideoDepacketizer {
    /** Packets dropped as malformed, out of order or oversize (diagnostics only). */
    public var dropped: Long = 0
        protected set

    /** Feeds one RTP packet; returns the complete NAL units it finished, each prefixed with a start code. */
    public abstract fun push(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): List<ByteArray>

    public companion object {
        public const val MAX_NAL: Int = 2 * 1024 * 1024
        public val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)
    }

    protected fun annexB(p: ByteArray, from: Int, n: Int): ByteArray {
        val out = ByteArray(4 + n)
        out[3] = 1
        p.copyInto(out, 4, from, from + n)
        return out
    }
}

/**
 * H.264 over RTP (RFC 6184) as cameras send it on an interleaved RTSP connection: single NAL units, STAP-A aggregates and FU-A fragments.
 */
public class H264Depacketizer : VideoDepacketizer() {
    private var fragment: ByteArray? = null
    private var fragmentLen = 0
    private var expectedSeq = -1

    override fun push(packet: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val rtp = RtpPayload.locate(packet, offset, length) ?: run { dropped++; return emptyList() }
        val start = rtp.start
        val end = rtp.end

        // A gap in sequence numbers breaks any fragment in progress.
        if (expectedSeq >= 0 && rtp.seq != expectedSeq && fragment != null) { fragment = null; dropped++ }
        expectedSeq = (rtp.seq + 1) and 0xffff

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
}

/**
 * H.265 over RTP (RFC 7798): single NAL units, aggregation packets (type 48) and fragmentation units (type 49). A NAL's header is two bytes (type in bits 1 to 6 of
 * the first), so a fragment's header is rebuilt from the packet's payload header and the fragmentation header's type. The "donl" fields of the interleaved mode
 * (`sprop-max-don-diff` above 0) are not used by cameras and are not supported; PACI packets (type 50) are dropped.
 */
public class H265Depacketizer : VideoDepacketizer() {
    private var fragment: ByteArray? = null
    private var fragmentLen = 0
    private var expectedSeq = -1

    override fun push(packet: ByteArray, offset: Int, length: Int): List<ByteArray> {
        val rtp = RtpPayload.locate(packet, offset, length) ?: run { dropped++; return emptyList() }
        val start = rtp.start
        val end = rtp.end

        if (expectedSeq >= 0 && rtp.seq != expectedSeq && fragment != null) { fragment = null; dropped++ }
        expectedSeq = (rtp.seq + 1) and 0xffff

        // Every packet starts with a two-byte header whose first bit (forbidden_zero_bit) must be 0.
        if (end - start < 2 || packet[start].toInt() and 0x80 != 0) { dropped++; return emptyList() }
        val type = (packet[start].toInt() ushr 1) and 0x3f
        return when {
            type <= 47 -> listOf(annexB(packet, start, end - start)) // a NAL unit, header included (some, like end-of-sequence, are only the header)
            type == 48 -> aggregate(packet, start + 2, end)
            type == 49 -> fragmentation(packet, start, end)
            else -> { dropped++; emptyList() }
        }
    }

    private fun aggregate(p: ByteArray, from: Int, end: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(3)
        var i = from
        while (i + 2 <= end) {
            val size = ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
            i += 2
            if (size < 2 || i + size > end) { dropped++; break }
            out += annexB(p, i, size)
            i += size
        }
        return out
    }

    private fun fragmentation(p: ByteArray, start: Int, end: Int): List<ByteArray> {
        if (end - start < 4) { dropped++; return emptyList() }
        val h0 = p[start].toInt() and 0xff
        val h1 = p[start + 1].toInt() and 0xff
        val fuHeader = p[start + 2].toInt() and 0xff
        val first = fuHeader and 0x80 != 0
        val last = fuHeader and 0x40 != 0
        val fuType = fuHeader and 0x3f
        if (fuType > 47) { fragment = null; dropped++; return emptyList() }
        val data = start + 3
        if (first) {
            fragment = ByteArray(1024).also { it[3] = 1; it[4] = ((h0 and 0x81) or (fuType shl 1)).toByte(); it[5] = h1.toByte() }
            fragmentLen = 6
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
}

/** What a camera's SDP says about its video, reduced to what playing it needs. [parameterSets] are the codec's own (H.264: SPS, PPS; H.265: VPS, SPS, PPS), each one NAL without a start code. */
public data class SdpVideo(val control: String, val payloadType: Int, val parameterSets: List<ByteArray>, val codec: VideoCodec = VideoCodec.H264) {
    /** The parameter sets as Annex-B, to send to the decoder before the first frame (cameras repeat them in-band too, but not always at the start). */
    public fun parameterSetsAnnexB(): ByteArray = parameterSets.fold(ByteArray(0)) { acc, ps -> acc + VideoDepacketizer.START_CODE + ps }

    public companion object {
        private const val MAX_SDP = 16 * 1024

        /**
         * Finds the first video media section the player can use (H.264 or H.265). The control URL is kept only if it is relative or `*`: an absolute one from
         * the network is never followed.
         */
        public fun parse(sdp: String): SdpVideo? {
            if (sdp.length > MAX_SDP) return null
            val lines = sdp.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            var i = 0
            while (i < lines.size) {
                if (!lines[i].startsWith("m=video ")) { i++; continue }
                val pt = lines[i].split(' ').getOrNull(3)?.toIntOrNull()?.takeIf { it in 0..127 }
                var control: String? = null
                var codec: VideoCodec? = null
                var fmtp: Map<String, String> = emptyMap()
                i++
                while (i < lines.size && !lines[i].startsWith("m=")) {
                    val l = lines[i]
                    if (pt != null) when {
                        l.startsWith("a=control:") -> control = l.removePrefix("a=control:").trim()
                        l.startsWith("a=rtpmap:$pt ") -> {
                            val name = l.substringAfter(' ').uppercase()
                            codec = if (name.startsWith("H264/")) VideoCodec.H264 else if (name.startsWith("H265/") || name.startsWith("HEVC/")) VideoCodec.H265 else null
                        }
                        l.startsWith("a=fmtp:$pt ") -> fmtp = fmtpParameters(l.substringAfter(' '))
                    }
                    i++
                }
                if (pt == null || codec == null) continue // not a video track we can play; look at the next one
                val c = control ?: "*"
                val safe = if (c == "*" || c.none { it == ':' || it == ' ' || it.isISOControl() } && !c.startsWith("/") && !c.contains("..")) c else return null
                // The fmtp line may come before the rtpmap line, so the parameter sets are picked once the codec is known.
                val keys = if (codec == VideoCodec.H264) listOf("sprop-parameter-sets") else listOf("sprop-vps", "sprop-sps", "sprop-pps")
                val sets = keys.flatMap { k -> (fmtp[k] ?: "").split(',').take(4).mapNotNull { decodeBase64(it.trim()) }.filter { it.isNotEmpty() && it.size <= 1024 } }
                return SdpVideo(safe, pt, sets, codec)
            }
            return null
        }

        /** The "key=value" parameters of an fmtp line (separated by semicolons), keys lower-cased. */
        private fun fmtpParameters(fmtp: String): Map<String, String> =
            fmtp.split(';').map { it.trim() }.filter { '=' in it }.associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }

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
