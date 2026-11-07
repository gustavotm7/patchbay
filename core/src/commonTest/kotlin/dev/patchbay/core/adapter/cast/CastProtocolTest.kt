package dev.patchbay.core.adapter.cast

import dev.patchbay.core.result.PatchbayResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastProtocolTest {

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun frameMatchesAHandBuiltProtobuf() {
        // CastMessage{ version 0, source "s", destination "d", namespace "n", type STRING, payload "{}" }, built by hand:
        // 08 00 | 12 01 73 | 1a 01 64 | 22 01 6e | 28 00 | 32 02 7b 7d  (17 bytes)
        val f = CastProtocol.frame(CastProtocol.Message("s", "d", "n", "{}"))
        assertEquals("00000011" + "0800" + "120173" + "1a0164" + "22016e" + "2800" + "32027b7d", hex(f))
    }

    @Test
    fun roundTripsAndKeepsUnicodeAndLongPayloads() {
        val long = """{"type":"X","pad":"${"é".repeat(5_000)}"}"""
        val m = CastProtocol.Message("sender-patchbay", "receiver-0", CastProtocol.NS_RECEIVER, long)
        val f = CastProtocol.frame(m)
        val len = (CastProtocol.frameLength(f.copyOf(4)) as PatchbayResult.Ok).value
        assertEquals(f.size - 4, len)
        assertEquals(m, (CastProtocol.decode(f.copyOfRange(4, f.size)) as PatchbayResult.Ok).value)
    }

    @Test
    fun frameLengthIsBounded() {
        assertIs<PatchbayResult.Err>(CastProtocol.frameLength(byteArrayOf(0, 0, 0)))
        assertIs<PatchbayResult.Err>(CastProtocol.frameLength(byteArrayOf(0, 0, 0, 0)))
        assertIs<PatchbayResult.Err>(CastProtocol.frameLength(byteArrayOf(0, 1, 0, 1)))
        assertIs<PatchbayResult.Ok<Int>>(CastProtocol.frameLength(byteArrayOf(0, 0, 0, 1)))
    }

    @Test
    fun decodeRejectsMalformedAndBinaryMessages() {
        val ok = CastProtocol.frame(CastProtocol.Message("s", "d", "n", "{}")).copyOfRange(4, 19)
        listOf(
            ByteArray(0), ok.copyOf(5), ok.copyOf(ok.size - 1),
            byteArrayOf(0x12, 0x7f, 0x73), // length runs past the end
            byteArrayOf(0x1b, 0x01), // wire type 3 (group)
            byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01),
            byteArrayOf(0x12, 0x02, 0xff.toByte(), 0xfe.toByte()), // not UTF-8
        ).forEach { assertIs<PatchbayResult.Err>(CastProtocol.decode(it)) }
        // payload_type = BINARY is refused.
        val binary = byteArrayOf(0x08, 0x00, 0x12, 0x01, 0x73, 0x1a, 0x01, 0x64, 0x22, 0x01, 0x6e, 0x28, 0x01, 0x3a, 0x01, 0x00)
        assertIs<PatchbayResult.Err>(CastProtocol.decode(binary))
    }

    @Test
    fun outboundJsonIsExact() {
        assertEquals("""{"type":"GET_STATUS","requestId":7}""", CastProtocol.getStatus(7))
        assertEquals("""{"type":"SET_VOLUME","requestId":2,"volume":{"level":0.25}}""", CastProtocol.setVolume(0.25, 2))
        assertEquals("""{"type":"SET_VOLUME","requestId":3,"volume":{"muted":true}}""", CastProtocol.setMuted(true, 3))
        assertEquals("""{"type":"PAUSE","requestId":4,"mediaSessionId":9}""", CastProtocol.mediaCommand("PAUSE", 9, 4))
        assertTrue(runCatching { CastProtocol.mediaCommand("LOAD", 1, 1) }.isFailure) // nothing beyond play, pause, stop
        assertEquals(1.0, Regex("\"level\":([0-9.]+)").find(CastProtocol.setVolume(5.0, 1))!!.groupValues[1].toDouble())
    }

    @Test
    fun parsesReceiverStatus() {
        val p = """{"type":"RECEIVER_STATUS","requestId":1,"status":{"volume":{"level":0.4,"muted":false},"applications":[{"displayName":"YouTube","transportId":"web-5","appId":"X"}]}}"""
        val r = (CastProtocol.parseReceiverStatus(p) as PatchbayResult.Ok).value
        assertEquals(CastProtocol.ReceiverStatus(0.4, false, "YouTube", "web-5"), r)
        val idle = (CastProtocol.parseReceiverStatus("""{"type":"RECEIVER_STATUS","status":{"volume":{"level":1.0,"muted":true}}}""") as PatchbayResult.Ok).value
        assertEquals(CastProtocol.ReceiverStatus(1.0, true, null, null), idle)
        listOf(
            """{"type":"RECEIVER_STATUS","status":{"volume":{"level":1.5}}}""", """{"type":"RECEIVER_STATUS","status":{"volume":{"level":"0.5"}}}""",
            """{"type":"RECEIVER_STATUS","status":{"volume":{"muted":"yes"}}}""", """{"type":"OTHER","status":{}}""", """{"type":"RECEIVER_STATUS"}""", "[]", "x",
        ).forEach { assertIs<PatchbayResult.Err>(CastProtocol.parseReceiverStatus(it), it) }
    }

    @Test
    fun parsesMediaStatus() {
        val p = """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playerState":"PLAYING","currentTime":12.5,"media":{"duration":240.0,"metadata":{"title":"Song","artist":"Band"}}}]}"""
        val m = (CastProtocol.parseMediaStatus(p) as PatchbayResult.Ok).value!!
        assertEquals(CastProtocol.MediaStatus("PLAYING", 3, 12.5, 240.0, "Song", "Band"), m)
        assertNull((CastProtocol.parseMediaStatus("""{"type":"MEDIA_STATUS","status":[]}""") as PatchbayResult.Ok).value)
        listOf(
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playerState":"EXPLODING"}]}""",
            """{"type":"MEDIA_STATUS","status":[{"playerState":"PLAYING"}]}""",
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":"3","playerState":"PLAYING"}]}""",
        ).forEach { assertIs<PatchbayResult.Err>(CastProtocol.parseMediaStatus(it), it) }
        // Negative or absurd times are dropped, not clamped.
        val odd = (CastProtocol.parseMediaStatus("""{"type":"MEDIA_STATUS","status":[{"mediaSessionId":1,"playerState":"PAUSED","currentTime":-4}]}""") as PatchbayResult.Ok).value!!
        assertNull(odd.positionSec)
    }

    @Test
    fun mutatedInputNeverThrows() {
        val rnd = Random(0x43415354)
        val seeds = listOf(
            CastProtocol.frame(CastProtocol.Message("receiver-0", "sender-patchbay", CastProtocol.NS_RECEIVER,
                """{"type":"RECEIVER_STATUS","status":{"volume":{"level":0.4,"muted":false},"applications":[{"displayName":"YouTube","transportId":"web-5"}]}}""")).copyOfRange(4, 200),
            """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":3,"playerState":"PLAYING","media":{"metadata":{"title":"x"}}}]}""".encodeToByteArray(),
        )
        repeat(25_000) {
            val b = seeds[it % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(4)) { _ -> b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size + 1)) else b
            CastProtocol.decode(cut)
            if (cut.size >= 4) CastProtocol.frameLength(cut.copyOf(4))
            val s = cut.decodeToString()
            CastProtocol.parseReceiverStatus(s); CastProtocol.parseMediaStatus(s); CastProtocol.messageType(s)
        }
        assertContentEquals(byteArrayOf(1), byteArrayOf(1))
    }
}
