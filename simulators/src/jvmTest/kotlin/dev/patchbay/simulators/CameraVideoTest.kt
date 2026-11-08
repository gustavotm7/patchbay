package dev.patchbay.simulators

import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.media.CameraVideo
import dev.patchbay.core.media.FfmpegLocator
import dev.patchbay.core.media.VideoDecoder
import dev.patchbay.core.model.StreamProtocol
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.errorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraVideoTest {
    private fun rtp(seq: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0x80.toByte(), 96, (seq shr 8).toByte(), seq.toByte(), 0, 0, 0, 0, 0, 0, 0, 1) + payload

    @Test
    fun locatorFindsAFfmpegInTheAppFolderBeforePath() {
        val app = Files.createTempDirectory("pbapp").toFile()
        val onPath = Files.createTempDirectory("pbpath").toFile()
        assertNull(FfmpegLocator.find(listOf(app), path = onPath.path, windows = false))
        File(onPath, "ffmpeg").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals(File(onPath, "ffmpeg"), FfmpegLocator.find(listOf(app), path = onPath.path, windows = false))
        File(app, "ffmpeg").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        assertEquals(File(app, "ffmpeg"), FfmpegLocator.find(listOf(app), path = onPath.path, windows = false))
        assertNull(FfmpegLocator.find(listOf(app), path = onPath.path, windows = true)) // a Windows build wants ffmpeg.exe
    }

    @Test
    fun withoutFfmpegTheMessageSaysWhatToDo(): Unit = runBlocking {
        val v = CameraVideo(NetGuard(), null)
        assertTrue(!v.available)
        val e = v.play(StreamLease("rtsp://127.0.0.1/live0", StreamProtocol.RTSP)) { }.errorOrNull()
        assertIs<PatchbayError.Unsupported>(e)
        assertTrue("ffmpeg" in e.message)
    }

    /** `cat` stands in for ffmpeg: whatever is written to it comes back, so JPEG-marked bytes in the "video" come out as frames. */
    @Test
    fun streamsThroughADecoderProcessAndSplitsFrames(): Unit = runBlocking {
        val cat = File("/bin/cat")
        if (!cat.exists()) return@runBlocking
        val sim = RtspSimulator("127.0.0.76", "cam", "secret", mode = RtspSimulator.Mode.NONE)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())
        sim.rtpPackets = listOf(rtp(1, byteArrayOf(0x41) + jpeg), rtp(2, byteArrayOf(0x41) + jpeg))
        try {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val video = CameraVideo(NetGuard(), cat, dev.patchbay.core.adapter.rtsp.RtspStreamClient.Config(connectTimeoutMs = 2_000, readTimeoutMs = 100, stallMs = 5_000))
            val job = async(Dispatchers.Default) {
                video.play(StreamLease("rtsp://${sim.address}:${sim.port}/${sim.path}", StreamProtocol.RTSP), VideoDecoder.withCommand(listOf("/bin/cat"))) { frames += it }
            }
            withTimeout(8_000) { while (frames.size < 2) delay(20) }
            assertTrue(frames.all { it.contentEquals(jpeg) })
            job.cancel()
            assertNotNull(frames.firstOrNull())
        } finally { sim.close() }
    }
}
