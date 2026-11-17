package dev.patchbay.core.adapter.rtsp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real RTP from an independent packetizer: [RealRtpVectors] holds the packets ffmpeg's RTP muxer produced for a short libx264 and libx265 encode (with small
 * packets, so aggregation and fragmentation occur), the NAL units the encoder itself wrote, and ffmpeg's SDP (tools/interop/rtp_vectors.py makes the file).
 * Patchbay's depacketizers must give back exactly the encoder's NAL units. (ffmpeg's RTP muxer can leave the zero byte of the next NAL's four-byte start code
 * attached to the NAL before it; a real NAL never ends in 0x00 and a decoder ignores it, so one trailing zero is accepted and nothing else.)
 */
class RealPacketizerTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun same(expected: ByteArray, actual: ByteArray) = actual.contentEquals(expected) || actual.contentEquals(expected + 0)

    private fun strip(annexB: ByteArray): ByteArray {
        assertContentEquals(byteArrayOf(0, 0, 0, 1), annexB.copyOf(4))
        return annexB.copyOfRange(4, annexB.size)
    }

    private val h264Packets = RealRtpVectors.H264_PACKETS.map(::hex)
    private val h264Nals = RealRtpVectors.H264_NALS.map(::hex)
    private val h265Packets = RealRtpVectors.H265_PACKETS.map(::hex)
    private val h265Nals = RealRtpVectors.H265_NALS.map(::hex)

    @Test
    fun h264PacketsGiveBackTheEncodersNalUnitsExactly() {
        val d = H264Depacketizer()
        val out = h264Packets.flatMap { d.push(it) }.map(::strip)
        assertEquals(h264Nals.size, out.size)
        h264Nals.indices.forEach { assertTrue(same(h264Nals[it], out[it]), "NAL $it") }
        assertEquals(0, d.dropped)
    }

    @Test
    fun h265PacketsGiveBackTheEncodersNalUnitsExactly() {
        val d = H265Depacketizer()
        val out = h265Packets.flatMap { d.push(it) }.map(::strip)
        assertEquals(h265Nals.size, out.size)
        h265Nals.indices.forEach { assertTrue(same(h265Nals[it], out[it]), "NAL $it") }
        assertEquals(0, d.dropped)
    }

    @Test
    fun theStreamsContainTheKindsOfPacketTheDepacketizersMustHandle() {
        val h264Kinds = h264Packets.map { it[12].toInt() and 0x1f }.toSet()
        assertTrue(24 in h264Kinds && 28 in h264Kinds, "H.264 vectors should have STAP-A and FU-A packets: $h264Kinds")
        val h265Kinds = h265Packets.map { (it[12].toInt() shr 1) and 0x3f }.toSet()
        assertTrue(48 in h265Kinds && 49 in h265Kinds, "H.265 vectors should have AP and FU packets: $h265Kinds")
    }

    @Test
    fun theSdpParameterSetsAreTheEncodersOwn() {
        val h264 = assertNotNull(SdpVideo.parse(RealRtpVectors.H264_SDP))
        assertEquals(VideoCodec.H264, h264.codec)
        val sps = h264Nals.first { (it[0].toInt() and 0x1f) == 7 }
        val pps = h264Nals.first { (it[0].toInt() and 0x1f) == 8 }
        assertEquals(2, h264.parameterSets.size)
        assertContentEquals(sps, h264.parameterSets[0]); assertContentEquals(pps, h264.parameterSets[1])

        val h265 = assertNotNull(SdpVideo.parse(RealRtpVectors.H265_SDP))
        assertEquals(VideoCodec.H265, h265.codec)
        val wanted = listOf(32, 33, 34).map { t -> h265Nals.first { ((it[0].toInt() shr 1) and 0x3f) == t } }
        assertEquals(3, h265.parameterSets.size)
        wanted.indices.forEach { assertContentEquals(wanted[it], h265.parameterSets[it], "parameter set $it") }
    }

    @Test
    fun aLostPacketCostsOnlyTheNalItBelongedTo() {
        // Drop one fragment from the middle of the first fragmented NAL: exactly that NAL goes missing, the rest are intact and in order.
        val h264Lost = h264Packets.indexOfFirst { (it[12].toInt() and 0x1f) == 28 && (it[13].toInt() and 0xC0) == 0 }
        val h265Lost = h265Packets.indexOfFirst { ((it[12].toInt() shr 1) and 0x3f) == 49 && (it[14].toInt() and 0xC0) == 0 }
        assertTrue(h264Lost > 0 && h265Lost > 0, "the vectors need a middle fragment")
        for ((packets, nals, depack, lost) in listOf(
            Quad(h264Packets, h264Nals, H264Depacketizer(), h264Lost), Quad(h265Packets, h265Nals, H265Depacketizer(), h265Lost),
        )) {
            val out = packets.filterIndexed { i, _ -> i != lost }.flatMap { depack.push(it) }.map(::strip)
            assertEquals(nals.size - 1, out.size)
            assertTrue(depack.dropped > 0)
            val missing = nals.indices.first { i -> i >= out.size || !same(nals[i], out[i]) }
            for (i in out.indices) assertTrue(same(nals[if (i < missing) i else i + 1], out[i]), "NAL $i after losing a fragment of NAL $missing")
        }
    }

    private data class Quad(val packets: List<ByteArray>, val nals: List<ByteArray>, val depack: VideoDepacketizer, val lost: Int)

    @Test
    fun damagedRealPacketsNeverThrow() {
        val rnd = Random(0x52545031)
        for (d in listOf<Pair<VideoDepacketizer, List<ByteArray>>>(H264Depacketizer() to h264Packets, H265Depacketizer() to h265Packets)) {
            repeat(25_000) {
                val p = d.second[it % d.second.size].copyOf()
                repeat(1 + rnd.nextInt(4)) { _ -> p[rnd.nextInt(p.size)] = rnd.nextInt(256).toByte() }
                d.first.push(if (rnd.nextInt(4) == 0) p.copyOf(rnd.nextInt(p.size + 1)) else p)
            }
        }
    }
}
