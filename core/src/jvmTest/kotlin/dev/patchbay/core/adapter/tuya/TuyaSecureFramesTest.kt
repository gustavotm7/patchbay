package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.result.PatchbayResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Known answers produced by the tinytuya library's own codec (tools/interop/tuya_vectors.py), so the 3.4 and 3.5 frames and session keys here are
 * checked against an independent implementation, not only against our simulator. Then every damaged or truncated frame is rejected, never thrown.
 */
class TuyaSecureFramesTest {
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val localKey = "0123456789abcdef".encodeToByteArray()
    private val session = bytes("00112233445566778899aabbccddeeff")
    private val iv = bytes("a0a1a2a3a4a5a6a7a8a9aaab")
    private val localNonce = bytes("101112131415161718191a1b1c1d1e1f")
    private val remoteNonce = bytes("202122232425262728292a2b2c2d2e2f")
    private val reply = """{"data":{"dps":{"20":true}},"protocol":4,"t":1700000000}""".encodeToByteArray()

    private val v34QueryEnc = bytes("c8a4a72941462caed8e6009da3c4bddd")
    private val v34QueryFrame = bytes("000055aa000000050000001000000034c8a4a72941462caed8e6009da3c4bddd54234c658d4f1ca06f8a14d74cb4a578a8ea436e2fb64e2f6d7ec9d3052272560000aa55")
    private val v34DeviceEnc = bytes("1ed545202cf8c63ff02cac38f7b61616aecfa0268434a3be1aa653cd3267caa0452dbe844dfc979b39ac35647e30a63c7eb902eb30b04d7c1f7ee0bb650ea39c")
    private val v34DeviceFrame = bytes(
        "000055aa000000050000001000000068000000001ed545202cf8c63ff02cac38f7b61616aecfa0268434a3be1aa653cd3267caa0452dbe844dfc979b39ac35647e30a63c7eb902eb30b04d7c1f7ee0bb650ea39c" +
            "3d8f5810ca82a4c68e1ce753f578c13c21f73bdb9314e29fbecd6743dbf2691c0000aa55",
    )
    private val v35ClientFrame = bytes(
        "000066990000000000070000000d0000005ba0a1a2a3a4a5a6a7a8a9aaab3022b0f78f6fd7d00b6cc81f976336c5f343389e7a9b305e33041e0a829e69ac1c1a825c75c5a036e048712dcf94b57028c756706bda3347e2a4221036eaf8cbad40c15ac6dd5864543dca0b55bcf400009966",
    )
    private val v35DeviceFrame = bytes(
        "000066990000000000070000001000000058a0a1a2a3a4a5a6a7a8a9aaab030c85f7f44db3b17f0dea25ec4152cea211708a2cc663136552564acbc160a2045bdc1165cbb738ae50303b87c6b270288d43726bc83911b3f8610878b5f174f088c7969ed50b07408d9ce800009966",
    )

    @Test
    fun protocol34FramesMatchTheReferenceLibrary() {
        assertContentEquals(v34QueryEnc, TuyaCipher.encrypt(session, "{}".encodeToByteArray()))
        assertContentEquals(v34QueryFrame, TuyaSecureFrames.encode34(5, TuyaProtocol.CMD_DP_QUERY_NEW, v34QueryEnc, session))

        val f = (TuyaSecureFrames.decode34(v34DeviceFrame, session) as PatchbayResult.Ok).value
        assertEquals(5, f.seq); assertEquals(0x10, f.cmd); assertEquals(0, f.retcode)
        assertContentEquals(v34DeviceEnc, f.payload)
        assertContentEquals(reply, TuyaCipher.decrypt(session, f.payload))
        val dps = (TuyaProtocol.parseDps(reply) as PatchbayResult.Ok).value
        assertEquals(setOf("20"), dps.keys)

        // The header check reads the same frame's length field.
        assertEquals(PatchbayResult.Ok(v34DeviceFrame.size - TuyaSecureFrames.HEADER_34), TuyaSecureFrames.remaining34(v34DeviceFrame.copyOf(TuyaSecureFrames.HEADER_34)))
    }

    @Test
    fun protocol35FramesMatchTheReferenceLibrary() {
        val control = TuyaProtocol.versionHeader("3.5") + """{"protocol":5,"t":1,"data":{"dps":{"20":false}}}""".encodeToByteArray()
        assertContentEquals(v35ClientFrame, TuyaSecureFrames.encode35(7, TuyaProtocol.CMD_CONTROL_NEW, control, session, iv))

        val f = (TuyaSecureFrames.decode35(v35DeviceFrame, session) as PatchbayResult.Ok).value
        assertEquals(7, f.seq); assertEquals(0x10, f.cmd); assertEquals(0, f.retcode)
        assertContentEquals(reply, f.payload)

        assertEquals(PatchbayResult.Ok(v35DeviceFrame.size - TuyaSecureFrames.HEADER_35), TuyaSecureFrames.remaining35(v35DeviceFrame.copyOf(TuyaSecureFrames.HEADER_35)))
    }

    @Test
    fun sessionKeysMatchTheReferenceLibrary() {
        assertContentEquals(bytes("176c295a595d7193d095f1ce05665ab6"), TuyaLinks.sessionKey(TuyaVersion.V34, localKey, localNonce, remoteNonce))
        assertContentEquals(bytes("a1f95f083497b6a15a1b28ac45d9819a"), TuyaLinks.sessionKey(TuyaVersion.V35, localKey, localNonce, remoteNonce))
        assertContentEquals(bytes("65635198bf06f585e07e8c9aeaab41ead0b27ea52a8cf149d76cd900014bd54d"), TuyaCipher.hmacSha256(localKey, localNonce))
        assertContentEquals(bytes("83a8996a34be02da6fcb8dea92330a685ec3c823c31f76992697fd7dac2cdfc5"), TuyaCipher.hmacSha256(localKey, remoteNonce))
    }

    @Test
    fun aFrameOpenedWithTheWrongKeyIsRejected() {
        val other = bytes("ffeeddccbbaa99887766554433221100")
        assertIs<PatchbayResult.Err>(TuyaSecureFrames.decode34(v34DeviceFrame, other))
        assertIs<PatchbayResult.Err>(TuyaSecureFrames.decode35(v35DeviceFrame, other))
    }

    @Test
    fun everySingleDamagedByteIsRejected() {
        for (i in v34DeviceFrame.indices) {
            val bad = v34DeviceFrame.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertIs<PatchbayResult.Err>(TuyaSecureFrames.decode34(bad, session), "3.4 byte $i")
        }
        for (i in v35DeviceFrame.indices) {
            val bad = v35DeviceFrame.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertIs<PatchbayResult.Err>(TuyaSecureFrames.decode35(bad, session), "3.5 byte $i")
        }
        for (cut in 0 until v34DeviceFrame.size) assertIs<PatchbayResult.Err>(TuyaSecureFrames.decode34(v34DeviceFrame.copyOf(cut), session), "3.4 cut $cut")
        for (cut in 0 until v35DeviceFrame.size) assertIs<PatchbayResult.Err>(TuyaSecureFrames.decode35(v35DeviceFrame.copyOf(cut), session), "3.5 cut $cut")
    }

    @Test
    fun headerChecksBoundTheLength() {
        fun withLength(frame: ByteArray, at: Int, n: Int) = frame.copyOf().also { f -> for (k in 0 until 4) f[at + k] = (n ushr (24 - 8 * k)).toByte() }
        val h34 = v34DeviceFrame.copyOf(TuyaSecureFrames.HEADER_34)
        val h35 = v35DeviceFrame.copyOf(TuyaSecureFrames.HEADER_35)
        // Smallest sensible length: 3.4 is a return code, the HMAC and the suffix (40); 3.5 is the IV, a return code and the tag (32).
        for (n in listOf(0, 1, 39, 0x00FFFFFF, -1, Int.MAX_VALUE)) assertIs<PatchbayResult.Err>(TuyaSecureFrames.remaining34(withLength(h34, 12, n)), "3.4 length $n")
        for (n in listOf(0, 1, 31, 0x00FFFFFF, -1, Int.MAX_VALUE)) assertIs<PatchbayResult.Err>(TuyaSecureFrames.remaining35(withLength(h35, 14, n)), "3.5 length $n")
        assertTrue(TuyaSecureFrames.remaining34(withLength(h34, 12, 40)) is PatchbayResult.Ok)
        assertTrue(TuyaSecureFrames.remaining35(withLength(h35, 14, 32)) is PatchbayResult.Ok)
        assertTrue(TuyaSecureFrames.remaining34(h34) is PatchbayResult.Ok)
        assertTrue(TuyaSecureFrames.remaining35(h35) is PatchbayResult.Ok)
    }

    @Test
    fun mutatedFramesNeverThrow() {
        val rnd = Random(0x34353635)
        val seeds = listOf(v34DeviceFrame, v34QueryFrame, v35DeviceFrame, v35ClientFrame)
        repeat(25_000) {
            val b = seeds[it % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(4)) { _ -> b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size + 1)) else b
            TuyaSecureFrames.decode34(cut, session); TuyaSecureFrames.decode35(cut, session)
            if (cut.size >= TuyaSecureFrames.HEADER_34) TuyaSecureFrames.remaining34(cut.copyOf(TuyaSecureFrames.HEADER_34))
            if (cut.size >= TuyaSecureFrames.HEADER_35) TuyaSecureFrames.remaining35(cut.copyOf(TuyaSecureFrames.HEADER_35))
            TuyaProtocol.parseDps(cut); TuyaProtocol.parseDps(TuyaProtocol.stripVersionHeader(cut))
        }
    }
}
