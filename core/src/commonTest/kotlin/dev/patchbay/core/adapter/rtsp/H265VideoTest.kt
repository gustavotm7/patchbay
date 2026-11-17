package dev.patchbay.core.adapter.rtsp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** H.265 over RTP (RFC 7798): hand-built packets for each case, and the SDP forms cameras send. Real packets from ffmpeg's packetizer are in [RealPacketizerTest]. */
class H265VideoTest {
    private fun rtp(seq: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte(); h[1] = 96
        h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
        return h + payload
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun annexB(vararg v: Int) = byteArrayOf(0, 0, 0, 1) + b(*v)

    // NAL header bytes: F(1) type(6) layer(6) tid(3). Type 19 (IDR_W_RADL) is 0x26 0x01; type 1 (TRAIL_R) is 0x02 0x01; 49 (FU) is 0x62 0x01; 48 (AP) is 0x60 0x01.
    @Test
    fun aSingleNalUnitBecomesAnnexB() {
        val out = H265Depacketizer().push(rtp(1, b(0x26, 0x01, 9, 8, 7)))
        assertContentEquals(annexB(0x26, 0x01, 9, 8, 7), out.single())
    }

    @Test
    fun aNalThatIsOnlyItsHeaderIsKept() {
        // End of sequence (type 36) has no payload at all.
        assertContentEquals(annexB(0x48, 0x01), H265Depacketizer().push(rtp(1, b(0x48, 0x01))).single())
    }

    @Test
    fun anAggregationPacketUnpacksEveryNalAndBadSizesStopIt() {
        val ap = b(0x60, 0x01, 0, 3, 0x40, 0x01, 0xAA, 0, 4, 0x42, 0x01, 1, 2)
        val out = H265Depacketizer().push(rtp(5, ap))
        assertEquals(2, out.size)
        assertContentEquals(annexB(0x40, 0x01, 0xAA), out[0])
        assertContentEquals(annexB(0x42, 0x01, 1, 2), out[1])

        val d = H265Depacketizer()
        assertEquals(0, d.push(rtp(1, b(0x60, 0x01, 0, 9, 0x40, 0x01))).size) // claims more than it holds
        assertEquals(0, d.push(rtp(2, b(0x60, 0x01, 0, 1, 0x40))).size) // a NAL is at least its two header bytes
        assertTrue(d.dropped >= 2)
    }

    @Test
    fun fragmentsAreJoinedAndTheNalHeaderIsRebuiltFromTheFragmentationHeader() {
        val d = H265Depacketizer()
        // Payload header: type 49, layer 0, tid 1 (0x62 0x01). FU header: S, E, type of the original NAL (19).
        assertEquals(0, d.push(rtp(10, b(0x62, 0x01, 0x80 or 19, 1, 2))).size)
        assertEquals(0, d.push(rtp(11, b(0x62, 0x01, 19, 3, 4))).size)
        val out = d.push(rtp(12, b(0x62, 0x01, 0x40 or 19, 5)))
        assertContentEquals(annexB(0x26, 0x01, 1, 2, 3, 4, 5), out.single()) // the original header: type 19, layer 0, tid 1
        assertEquals(0, d.dropped)
    }

    @Test
    fun theLayerBitsOfTheOriginalNalComeFromThePayloadHeader() {
        // Layer id 1 sets the low bit of the first header byte and the top bit of the second (0x63 0x09 here: type 49, layer 1, tid 1).
        val d = H265Depacketizer()
        d.push(rtp(1, b(0x63, 0x09, 0x80 or 1, 7)))
        val out = d.push(rtp(2, b(0x63, 0x09, 0x40 or 1, 8)))
        assertContentEquals(annexB(0x03, 0x09, 7, 8), out.single()) // type 1 with the layer bit kept
    }

    @Test
    fun aLostFragmentDropsTheWholeNalAndTheNextPictureIsFine() {
        val d = H265Depacketizer()
        d.push(rtp(10, b(0x62, 0x01, 0x80 or 19, 1)))
        assertEquals(0, d.push(rtp(12, b(0x62, 0x01, 0x40 or 19, 2))).size) // 11 never arrived
        assertTrue(d.dropped > 0)
        assertEquals(1, d.push(rtp(13, b(0x02, 0x01, 9))).size)
    }

    @Test
    fun aMiddleOrEndFragmentWithoutAStartIsDropped() {
        val d = H265Depacketizer()
        assertEquals(0, d.push(rtp(1, b(0x62, 0x01, 19, 1))).size)
        assertEquals(0, d.push(rtp(2, b(0x62, 0x01, 0x40 or 19, 1))).size)
        assertEquals(2, d.dropped)
    }

    @Test
    fun paciReservedAndForbiddenBitPacketsAreDroppedWithoutThrowing() {
        val d = H265Depacketizer()
        assertEquals(0, d.push(rtp(1, b(50 shl 1, 0x01, 1, 2))).size) // PACI
        assertEquals(0, d.push(rtp(2, b(60 shl 1, 0x01, 1, 2))).size) // reserved
        assertEquals(0, d.push(rtp(3, b(0x80 or 0x26, 0x01, 1))).size) // forbidden_zero_bit set
        assertEquals(0, d.push(rtp(4, b(0x62, 0x01, 0x80 or 49, 1))).size) // a fragment of a fragment
        assertEquals(0, d.push(rtp(5, b(0x62, 0x01))).size) // header only, no fragmentation header
        assertEquals(0, d.push(rtp(6, b(0x26))).size) // one byte
        assertEquals(0, d.push(ByteArray(5)).size)
        assertEquals(7, d.dropped)
    }

    @Test
    fun anOversizeFragmentedNalIsDropped() {
        val d = H265Depacketizer()
        val big = ByteArray(60_000) { 1 }
        var seq = 0
        d.push(rtp(seq++, b(0x62, 0x01, 0x80 or 19) + big))
        var out = emptyList<ByteArray>()
        repeat(60) { out = d.push(rtp(seq++, b(0x62, 0x01, 19) + big)) }
        assertEquals(0, out.size)
        assertTrue(d.dropped > 0)
    }

    @Test
    fun randomPacketsNeverThrow() {
        val rnd = Random(21)
        val d = H265Depacketizer()
        repeat(25_000) {
            val data = rnd.nextBytes(rnd.nextInt(0, 90))
            if (data.size > 1 && rnd.nextInt(3) == 0) data[0] = (0x80 or (data[0].toInt() and 0x3f)).toByte()
            if (data.size > 13 && rnd.nextInt(2) == 0) data[12] = (rnd.nextInt(2) * 0x60 + 0x60 + rnd.nextInt(2) * 2).toByte() // often an AP or an FU header
            d.push(data)
        }
    }

    // ---- SDP ---------------------------------------------------------------------------------

    private val sdp = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=cam
        m=video 0 RTP/AVP 96
        a=rtpmap:96 H265/90000
        a=fmtp:96 sprop-vps=QAEMAf//AWAAAAMAkAAAAwAAAwBalZgJ; sprop-sps=QgEBAWAAAAMAkAAAAwAAAwBaoAUCAeFllZpJMrwFoCAAAAMAIAAAAwHh; sprop-pps=RAHBcrRiQA==
        a=control:trackID=1
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun anH265SdpGivesTheCodecTheTrackAndThreeParameterSets() {
        val v = assertNotNull(SdpVideo.parse(sdp))
        assertEquals(VideoCodec.H265, v.codec)
        assertEquals("trackID=1", v.control)
        assertEquals(3, v.parameterSets.size)
        assertEquals(32, (v.parameterSets[0][0].toInt() shr 1) and 0x3f) // VPS
        assertEquals(33, (v.parameterSets[1][0].toInt() shr 1) and 0x3f) // SPS
        assertEquals(34, (v.parameterSets[2][0].toInt() shr 1) and 0x3f) // PPS
        assertEquals(3 * 4 + v.parameterSets.sumOf { it.size }, v.parameterSetsAnnexB().size)
    }

    @Test
    fun theFmtpLineMayComeBeforeTheRtpmapLineAndHevcIsAnAcceptedName() {
        val reordered = "v=0\r\nm=video 0 RTP/AVP 98\r\na=fmtp:98 sprop-sps=QgEBAWAAAAMAkAAAAwAAAwBaoAUCAeFllZpJMrwFoCAAAAMAIAAAAwHh\r\na=rtpmap:98 HEVC/90000\r\n"
        val v = assertNotNull(SdpVideo.parse(reordered))
        assertEquals(VideoCodec.H265, v.codec)
        assertEquals(1, v.parameterSets.size)
        assertEquals(98, v.payloadType)
    }

    @Test
    fun anH264SdpStillGivesH264AndTheFirstPlayableVideoSectionWins() {
        val two = "v=0\r\nm=video 0 RTP/AVP 26\r\na=rtpmap:26 JPEG/90000\r\nm=video 0 RTP/AVP 97\r\na=rtpmap:97 H264/90000\r\na=fmtp:97 packetization-mode=1; sprop-parameter-sets=Z2QAKKw=,aO48gA==\r\n"
        val v = assertNotNull(SdpVideo.parse(two))
        assertEquals(VideoCodec.H264, v.codec)
        assertEquals(97, v.payloadType)
        assertEquals(2, v.parameterSets.size)
    }

    @Test
    fun noPlayableVideoOrAnUnsafeControlIsRefused() {
        assertNull(SdpVideo.parse("v=0\r\nm=video 0 RTP/AVP 26\r\na=rtpmap:26 JPEG/90000\r\n"))
        assertNull(SdpVideo.parse(sdp.replace("trackID=1", "rtsp://203.0.113.9/steal")))
        assertNull(SdpVideo.parse(sdp.replace("trackID=1", "../x")))
        assertEquals(0, SdpVideo.parse(sdp.replace("sprop-vps=QAEMAf//AWAAAAMAkAAAAwAAAwBalZgJ", "sprop-vps=!!!"))!!.parameterSets.size - 2)
    }
}
