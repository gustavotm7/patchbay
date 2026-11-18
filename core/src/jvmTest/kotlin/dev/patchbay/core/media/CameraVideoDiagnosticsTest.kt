package dev.patchbay.core.media

import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.model.StreamProtocol
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraVideoDiagnosticsTest {
    @Test
    fun aMissingDecoderIsOnTheRecordWithoutTheCameraLogin(): Unit = runBlocking {
        val video = CameraVideo(NetGuard(), ffmpeg = null)
        val r = video.play(StreamLease("rtsp://user:s3cret!pw@192.168.1.20:554/live", StreamProtocol.RTSP)) {}
        assertTrue(r is PatchbayResult.Err)
        val text = video.diagnostics().joinToString("\n")
        assertTrue("ffmpeg found: false" in text && "problem: no ffmpeg found" in text, text)
        assertFalse("s3cret" in text || "192.168" in text)
    }

    @Test
    fun diagnosticsSayWhetherFfmpegIsBesidePatchbayOrOnPath(): Unit {
        val dir = java.nio.file.Files.createTempDirectory("pbffmpeg").toFile()
        val exe = java.io.File(dir, "ffmpeg").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        // Found in the folder the app was given, not through PATH.
        val beside = CameraVideo(NetGuard(), ffmpeg = FfmpegLocator.find(listOf(dir), path = ""))
        assertTrue("ffmpeg found: true (beside Patchbay)" in beside.diagnostics().joinToString("\n"), beside.diagnostics().toString())
        assertTrue(exe.isFile)
    }
}
