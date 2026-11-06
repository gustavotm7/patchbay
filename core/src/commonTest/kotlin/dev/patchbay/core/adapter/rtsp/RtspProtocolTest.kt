package dev.patchbay.core.adapter.rtsp

import dev.patchbay.core.result.PatchbayResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtspProtocolTest {

    private fun response(vararg lines: String) = (lines.joinToString("\r\n") + "\r\n\r\n").encodeToByteArray()

    @Test
    fun describeIsExactBytes() {
        val expected = "DESCRIBE rtsp://192.168.1.50:554/live0 RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: Patchbay\r\n\r\n"
        assertEquals(expected, RtspProtocol.describe("rtsp://192.168.1.50:554/live0", 2).decodeToString())
        assertTrue(RtspProtocol.describe("rtsp://h:554/p", 1, "Basic abc").decodeToString().contains("Authorization: Basic abc\r\n"))
    }

    @Test
    fun describeRefusesHeaderInjection() {
        assertFailsWith { RtspProtocol.describe("rtsp://h:554/p\r\nX: y", 1) }
        assertFailsWith { RtspProtocol.describe("rtsp://h:554/p", 1, "Basic a\r\nX: y") }
    }

    private fun assertFailsWith(block: () -> Unit) {
        val threw = try { block(); false } catch (e: IllegalArgumentException) { true }
        assertTrue(threw)
    }

    @Test
    fun pathAndCredentialValidation() {
        assertTrue(RtspProtocol.isSafePath("live0"))
        assertTrue(RtspProtocol.isSafePath("stream/main_1"))
        listOf("", "/live0", "a b", "../x", "a?b", "a\r\nb", "x".repeat(65)).forEach { assertFalse(RtspProtocol.isSafePath(it), it) }
        assertTrue(RtspProtocol.isSafeCredential("p@ss:w0rd!"))
        listOf("", "a b", "a\"b", "a\\b", "a\nb", "x".repeat(65)).forEach { assertFalse(RtspProtocol.isSafeCredential(it), it) }
    }

    @Test
    fun parsesAResponse() {
        val r = RtspProtocol.parseResponse(response("RTSP/1.0 401 Unauthorized", "CSeq: 2", "WWW-Authenticate: Digest realm=\"cam\", nonce=\"abc\"", "WWW-Authenticate: Basic realm=\"cam\""))
        assertIs<PatchbayResult.Ok<RtspProtocol.Response>>(r)
        assertEquals(401, r.value.status)
        assertEquals(2, r.value.header("www-authenticate").size)
        assertEquals(200, (RtspProtocol.parseResponse(response("RTSP/1.0 200 OK")) as PatchbayResult.Ok).value.status)
    }

    @Test
    fun rejectsMalformedResponses() {
        listOf(
            ByteArray(0),
            ByteArray(RtspProtocol.MAX_HEADER_BYTES + 1) { 'a'.code.toByte() },
            "RTSP/1.0 200 OK\r\nCSeq: 1\r\n".encodeToByteArray(), // no blank line
            response("HTTP/1.1 200 OK"),
            response("RTSP/1.0 20 OK"),
            response("RTSP/1.0 200 OK", "no colon here"),
            response("RTSP/1.0 200 OK", "Bad Name: x"),
            byteArrayOf(0x52, 0x54, 0x53, 0x50, -1, -2, 13, 10, 13, 10),
            response("RTSP/1.0 200 OK", *Array(70) { "H$it: v" }),
        ).forEach { assertIs<PatchbayResult.Err>(RtspProtocol.parseResponse(it)) }
    }

    @Test
    fun choosesDigestOverBasic() {
        val c = RtspProtocol.parseChallenge(listOf("Basic realm=\"cam\"", "Digest realm=\"cam\", nonce=\"n1\", qop=\"auth,auth-int\", opaque=\"o\""))
        assertEquals(RtspProtocol.Challenge.Digest("cam", "n1", true, "o"), c)
        assertEquals(RtspProtocol.Challenge.Basic("cam"), RtspProtocol.parseChallenge(listOf("Basic realm=\"cam\"")))
        assertEquals(RtspProtocol.Challenge.Digest("r", "n", false, null), RtspProtocol.parseChallenge(listOf("Digest realm=r, nonce=n")))
    }

    @Test
    fun ignoresUnusableChallenges() {
        assertNull(RtspProtocol.parseChallenge(emptyList()))
        assertNull(RtspProtocol.parseChallenge(listOf("Digest realm=\"r\", nonce=\"n\", algorithm=SHA-256")))
        assertNull(RtspProtocol.parseChallenge(listOf("Digest realm=\"r\"")))
        assertNull(RtspProtocol.parseChallenge(listOf("Negotiate abc")))
        assertNotNull(RtspProtocol.parseChallenge(listOf("Digest realm=\"r\", nonce=\"n\", algorithm=md5")))
    }

    @Test
    fun mutatedInputNeverThrows() {
        val rnd = Random(0x52545350)
        val seeds = listOf(
            response("RTSP/1.0 401 Unauthorized", "CSeq: 2", "WWW-Authenticate: Digest realm=\"cam\", nonce=\"abc\", qop=\"auth\""),
            response("RTSP/1.0 200 OK", "CSeq: 1", "Content-Length: 0"),
        )
        repeat(25_000) {
            val b = seeds[it % seeds.size].copyOf()
            repeat(1 + rnd.nextInt(4)) { _ -> b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            val cut = if (rnd.nextInt(4) == 0) b.copyOf(rnd.nextInt(b.size + 1)) else b
            val r = RtspProtocol.parseResponse(cut)
            if (r is PatchbayResult.Ok) RtspProtocol.parseChallenge(r.value.header("www-authenticate"))
            RtspProtocol.parseChallenge(listOf(cut.decodeToString()))
        }
    }
}
