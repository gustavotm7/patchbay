package dev.patchbay.simulators

import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.adapter.rtsp.RtspStreamClient
import dev.patchbay.core.media.CameraVideo
import dev.patchbay.core.model.StreamProtocol
import dev.patchbay.core.net.NetGuard
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Interop with an independent RTP packetizer: tools/interop/rtsp_ffmpeg_server.py serves video that ffmpeg encoded and packetized, and this plays it with
 * our own RTSP client and writes what the client delivered (Annex-B) to PATCHBAY_RTSP_OUT, so the driver can compare it with the encoder's own
 * output and decode it with ffmpeg. Not part of CI: it runs only when PATCHBAY_RTSP_PORT and PATCHBAY_RTSP_OUT are set. See tools/interop/README.md.
 */
class RtspFfmpegInteropTest {
    @Test
    fun playsAnIndependentlyPacketizedEncode(): Unit = runBlocking {
        val port = System.getenv("PATCHBAY_RTSP_PORT")?.toIntOrNull() ?: skipInterop("PATCHBAY_RTSP_PORT is not set")
        val out = System.getenv("PATCHBAY_RTSP_OUT")?.let(::File) ?: skipInterop("PATCHBAY_RTSP_OUT is not set")
        val client = RtspStreamClient(NetGuard(), RtspStreamClient.Config(readTimeoutMs = 200, stallMs = 5_000))
        val delivered = java.io.ByteArrayOutputStream()
        var count = 0
        // The server hangs up after the last packet, so the stream ends with an error; what matters is what arrived before it.
        client.stream(StreamLease("rtsp://127.0.0.1:$port/live", StreamProtocol.RTSP)) { nal -> delivered.write(nal); count++ }
        out.writeBytes(delivered.toByteArray())
        File(out.path + ".info").writeText("nals=$count dropped=${client.droppedPackets}\n")
        assertTrue(count > 0, "the client delivered nothing")
    }

    /**
     * The whole camera-video path with a real decoder: our RTSP client feeds the real ffmpeg (PATCHBAY_FFMPEG), which must hand back JPEG pictures. Writes how many
     * pictures arrived and the codec the client reported to PATCHBAY_RTSP_OUT + ".frames".
     */
    @Test
    fun decodesThroughTheRealFfmpeg(): Unit = runBlocking {
        val port = System.getenv("PATCHBAY_RTSP_PORT")?.toIntOrNull() ?: skipInterop("PATCHBAY_RTSP_PORT is not set")
        val out = System.getenv("PATCHBAY_RTSP_OUT")?.let(::File) ?: skipInterop("PATCHBAY_RTSP_OUT is not set")
        val ffmpeg = System.getenv("PATCHBAY_FFMPEG")?.let(::File) ?: skipInterop("PATCHBAY_FFMPEG is not set")
        val video = CameraVideo(NetGuard(), ffmpeg, RtspStreamClient.Config(readTimeoutMs = 200, stallMs = 5_000))
        var frames = 0
        var jpegs = 0
        video.play(StreamLease("rtsp://127.0.0.1:$port/live", StreamProtocol.RTSP)) { jpeg ->
            frames++
            if (jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte() && jpeg[jpeg.size - 2] == 0xFF.toByte() && jpeg[jpeg.size - 1] == 0xD9.toByte()) jpegs++
        }
        File(out.path + ".frames").writeText("frames=$frames jpegs=$jpegs diagnostics=${video.diagnostics().first()}\n")
        assertTrue(frames > 0 && jpegs == frames, "the decoder gave $frames pictures, $jpegs of them whole JPEGs")
    }
}
