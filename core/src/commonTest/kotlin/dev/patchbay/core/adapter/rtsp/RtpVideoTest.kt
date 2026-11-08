package dev.patchbay.core.adapter.rtsp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtpVideoTest {
    private fun rtp(seq: Int, payload: ByteArray, marker: Boolean = false, pt: Int = 96, extra: ByteArray = ByteArray(0), flags: Int = 0x80): ByteArray {
        val h = ByteArray(12)
        h[0] = flags.toByte(); h[1] = ((if (marker) 0x80 else 0) or pt).toByte()
        h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
        return h + extra + payload
    }

    private fun annexB(vararg nal: Int) = byteArrayOf(0, 0, 0, 1) + nal.map { it.toByte() }.toByteArray()

    @Test
    fun singleNalBecomesAnnexB() {
        val d = H264Depacketizer()
        val out = d.push(rtp(1, byteArrayOf(0x65, 1, 2, 3)))
        assertEquals(1, out.size)
        assertContentEquals(annexB(0x65, 1, 2, 3), out[0])
    }

    @Test
    fun stapAUnpacksEveryNal() {
        val payload = byteArrayOf(24, 0, 2, 0x67, 0x42, 0, 3, 0x68, 0x11, 0x22)
        val out = H264Depacketizer().push(rtp(5, payload))
        assertContentEquals(annexB(0x67, 0x42), out[0])
        assertContentEquals(annexB(0x68, 0x11, 0x22), out[1])
    }

    @Test
    fun fuAFragmentsAreJoinedWithTheOriginalNalHeader() {
        val d = H264Depacketizer()
        val indicator = 0x7C // F=0, NRI=3, type 28
        val a = byteArrayOf(indicator.toByte(), 0x85.toByte(), 1, 2)       // start, original type 5
        val b = byteArrayOf(indicator.toByte(), 0x05, 3, 4)
        val c = byteArrayOf(indicator.toByte(), 0x45, 5)                   // end
        assertEquals(0, d.push(rtp(10, a)).size)
        assertEquals(0, d.push(rtp(11, b)).size)
        val out = d.push(rtp(12, c, marker = true))
        assertEquals(1, out.size)
        assertContentEquals(annexB(0x65, 1, 2, 3, 4, 5), out[0]) // NRI 3 from the indicator + type 5 from the FU header
    }

    @Test
    fun aLostFragmentDropsTheWholeNal() {
        val d = H264Depacketizer()
        d.push(rtp(10, byteArrayOf(0x7C, 0x85.toByte(), 1)))
        assertEquals(0, d.push(rtp(12, byteArrayOf(0x7C, 0x45, 2))).size) // 11 never arrived
        assertTrue(d.dropped > 0)
        // And the next picture is fine.
        assertEquals(1, d.push(rtp(13, byteArrayOf(0x41, 9))).size)
    }

    @Test
    fun headerExtensionsCsrcAndPaddingAreSkipped() {
        val extra = ByteArray(4) + ByteArray(4) // CSRC list (1 entry) is flagged via cc below; this is the extension header + 1 word
        val payload = byteArrayOf(0x41, 7, 7) + byteArrayOf(0, 0, 3) // 3 bytes of padding
        val flags = 0x80 or 0x20 or 0x10 or 0x01
        val h = rtp(1, ByteArray(0), flags = flags)
        val pkt = h + ByteArray(4) /* one CSRC */ + byteArrayOf(0xBE.toByte(), 0xDE.toByte(), 0, 1) + ByteArray(4) + payload
        val out = H264Depacketizer().push(pkt)
        assertContentEquals(annexB(0x41, 7, 7), out.single())
        assertEquals(8, extra.size)
    }

    @Test
    fun rejectsNonRtpAndBadPaddingWithoutThrowing() {
        val d = H264Depacketizer()
        assertEquals(0, d.push(ByteArray(5)).size)
        assertEquals(0, d.push(ByteArray(40) { 0x41 }).size) // version bits wrong
        assertEquals(0, d.push(rtp(1, byteArrayOf(0x41, 1, 0, 0, 0xFF.toByte()), flags = 0x80 or 0x20)).size) // padding longer than payload
        assertEquals(0, d.push(rtp(2, byteArrayOf(30, 1, 2))).size) // reserved NAL type
        assertEquals(0, d.push(rtp(3, byteArrayOf(24, 0, 9, 1))).size) // aggregate claims more than it holds
    }

    @Test
    fun randomPacketsNeverThrow() {
        val rnd = Random(11)
        val d = H264Depacketizer()
        repeat(25_000) {
            val data = rnd.nextBytes(rnd.nextInt(0, 90))
            if (data.size > 1 && rnd.nextInt(3) == 0) data[0] = (0x80 or (data[0].toInt() and 0x3f)).toByte()
            d.push(data)
        }
    }

    @Test
    fun oversizeFragmentedNalIsDropped() {
        val d = H264Depacketizer()
        val big = ByteArray(60_000) { 1 }
        var seq = 0
        d.push(rtp(seq++, byteArrayOf(0x7C, 0x85.toByte()) + big))
        var out = emptyList<ByteArray>()
        repeat(60) { out = d.push(rtp(seq++, byteArrayOf(0x7C, 0x05) + big)) }
        assertEquals(0, out.size)
        assertTrue(d.dropped > 0)
    }

    private val sdp = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=cam
        m=audio 0 RTP/AVP 8
        a=control:track2
        m=video 0 RTP/AVP 96
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1;profile-level-id=640028;sprop-parameter-sets=Z2QAKKw=,aO48gA==
        a=control:track1
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun sdpGivesTheVideoTrackAndParameterSets() {
        val v = assertNotNull(SdpVideo.parse(sdp))
        assertEquals("track1", v.control)
        assertEquals(96, v.payloadType)
        assertEquals(2, v.parameterSets.size)
        assertContentEquals(byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte()), v.parameterSets[0])
        assertEquals(4 + 5 + 4 + 4, v.parameterSetsAnnexB().size)
    }

    @Test
    fun sdpRefusesForeignControlUrlsAndNonH264() {
        assertNull(SdpVideo.parse(sdp.replace("track1", "rtsp://203.0.113.9/steal")))
        assertNull(SdpVideo.parse(sdp.replace("track1", "/abs")))
        assertNull(SdpVideo.parse(sdp.replace("H264/90000", "H265/90000")))
        assertNull(SdpVideo.parse("v=0\r\nm=audio 0 RTP/AVP 8\r\n"))
        assertNull(SdpVideo.parse("x".repeat(20_000)))
        assertEquals(0, SdpVideo.parse(sdp.replace("Z2QAKKw=,aO48gA==", "!!!notbase64,"))!!.parameterSets.size)
    }

    @Test
    fun mjpegSplitterFindsFramesAcrossChunks() {
        val jpeg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val jpeg2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 0xFF.toByte(), 0x00, 8, 0xFF.toByte(), 0xD9.toByte())
        val frames = mutableListOf<ByteArray>()
        val s = MjpegSplitter { frames += it }
        val all = byteArrayOf(5, 6) + jpeg1 + byteArrayOf(0) + jpeg2
        for (b in all) s.push(byteArrayOf(b)) // one byte at a time: the worst chunking
        assertEquals(2, frames.size)
        assertContentEquals(jpeg1, frames[0])
        assertContentEquals(jpeg2, frames[1])
    }

    @Test
    fun mjpegSplitterSurvivesGarbage() {
        val s = MjpegSplitter { }
        val rnd = Random(5)
        repeat(2_000) { s.push(rnd.nextBytes(rnd.nextInt(0, 400))) }
        s.push(ByteArray(10 * 1024 * 1024) { 0x11 }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }) // never ends: capped, no growth forever
    }
}
