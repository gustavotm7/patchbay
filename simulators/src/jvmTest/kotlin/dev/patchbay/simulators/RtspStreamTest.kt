package dev.patchbay.simulators

import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.adapter.rtsp.RtspStreamClient
import dev.patchbay.core.model.StreamProtocol
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RtspStreamTest {
    private fun rtp(seq: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0x80.toByte(), 96, (seq shr 8).toByte(), seq.toByte(), 0, 0, 0, 0, 0, 0, 0, 1) + payload

    private val packets = listOf(
        rtp(1, byteArrayOf(0x41, 9, 9)),                                               // a small NAL
        rtp(2, byteArrayOf(0x7C, 0x85.toByte(), 1, 2)),                                // FU-A start (IDR)
        rtp(3, byteArrayOf(0x7C, 0x45, 3)),                                            // FU-A end
    )

    private fun lease(sim: RtspSimulator, user: String = "cam", pass: String = "secret", path: String = sim.path) =
        StreamLease("rtsp://$user:$pass@${sim.address}:${sim.port}/$path", StreamProtocol.RTSP)

    private fun client() = RtspStreamClient(NetGuard(), RtspStreamClient.Config(connectTimeoutMs = 2_000, readTimeoutMs = 100, stallMs = 3_000, keepAliveMs = 200))

    @Test
    fun playsAndDeliversAnnexBNalsThenStopsOnCancel(): Unit = runBlocking {
        val sim = RtspSimulator("127.0.0.70", "cam", "secret", port = 0).also { it.rtpPackets = packets }
        try {
            val nals = CopyOnWriteArrayList<ByteArray>()
            val job = async(Dispatchers.Default) { client().stream(lease(sim)) { nals += it } }
            withTimeout(8_000) { while (nals.size < 3) delay(20) }
            assertEquals(listOf("DESCRIBE", "DESCRIBE", "SETUP", "PLAY"), sim.methods.take(4)) // the first DESCRIBE is the 401 challenge
            assertTrue(sim.requests.first().contains("Authorization").not()) // the login only follows a challenge
            // SPS+PPS from the SDP come first, then the stream.
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC.toByte(), 0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte()), nals[0])
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x41, 9, 9), nals[1])
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3), nals[2])
            withTimeout(8_000) { while ("OPTIONS" !in sim.methods) delay(20) } // keep-alive while playing
            job.cancel()
            kotlinx.coroutines.yield()
        } finally {
            sim.close()
        }
    }

    @Test
    fun aWrongPasswordIsRefusedAfterOneChallenge(): Unit = runBlocking {
        val sim = RtspSimulator("127.0.0.71", "cam", "secret")
        try {
            val r = client().stream(lease(sim, pass = "wrong")) { }
            assertIs<PatchbayError.Unauthorized>(r.errorOrNull())
            assertEquals(2, sim.methods.count { it == "DESCRIBE" }) // challenge, then one try; no hammering
        } finally { sim.close() }
    }

    @Test
    fun basicAuthWorksToo(): Unit = runBlocking {
        val sim = RtspSimulator("127.0.0.72", "cam", "secret", mode = RtspSimulator.Mode.BASIC).also { it.rtpPackets = packets }
        try {
            val nals = CopyOnWriteArrayList<ByteArray>()
            val job = async(Dispatchers.Default) { client().stream(lease(sim)) { nals += it } }
            withTimeout(8_000) { while (nals.size < 3) delay(20) }
            job.cancel()
        } finally { sim.close() }
    }

    @Test
    fun unknownPathHasAHelpfulMessage(): Unit = runBlocking {
        val sim = RtspSimulator("127.0.0.73", "cam", "secret")
        try {
            val e = client().stream(lease(sim, path = "nope")) { }.errorOrNull()
            assertIs<PatchbayError.Unreachable>(e)
            assertTrue("switched on" in e.message)
        } finally { sim.close() }
    }

    @Test
    fun junkInTheMediaStreamIsAProtocolViolation(): Unit = runBlocking {
        val sim = RtspSimulator("127.0.0.74", "cam", "secret").also { it.rtpPackets = packets; it.rawAfterPlay = "GET / HTTP/1.1\r\n".toByteArray() }
        try {
            assertIs<PatchbayError.ProtocolViolation>(client().stream(lease(sim)) { }.errorOrNull())
        } finally { sim.close() }
    }

    @Test
    fun aCameraThatStopsSendingEndsTheStream(): Unit = runBlocking {
        val sim = RtspSimulator("127.0.0.75", "cam", "secret", mode = RtspSimulator.Mode.NONE).also { it.rtpPackets = packets }
        try {
            val short = RtspStreamClient(NetGuard(), RtspStreamClient.Config(connectTimeoutMs = 2_000, readTimeoutMs = 100, stallMs = 600, keepAliveMs = 60_000))
            val r = withTimeout(8_000) { short.stream(StreamLease("rtsp://${sim.address}:${sim.port}/${sim.path}", StreamProtocol.RTSP)) { } }
            assertIs<PatchbayError.Timeout>(r.errorOrNull())
        } finally { sim.close() }
    }

    @Test
    fun refusesAddressesOffTheLanAndUnsafeLeases(): Unit = runBlocking {
        val c = client()
        assertIs<PatchbayError.NetGuardDenied>(c.stream(StreamLease("rtsp://8.8.8.8/live0", StreamProtocol.RTSP)) { }.errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(c.stream(StreamLease("http://192.168.1.5/live0", StreamProtocol.RTSP)) { }.errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(c.stream(StreamLease("rtsp://192.168.1.5/../etc", StreamProtocol.RTSP)) { }.errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(c.stream(StreamLease("rtsp://u@192.168.1.5/live0", StreamProtocol.RTSP)) { }.errorOrNull())
        assertTrue(c.stream(StreamLease("rtsp://cam.example.com/live0", StreamProtocol.RTSP)) { } is PatchbayResult.Err) // no DNS, ever
    }
}
