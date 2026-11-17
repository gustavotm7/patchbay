package dev.patchbay.core.media

import dev.patchbay.core.adapter.rtsp.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The decoder command line: the right input format for each codec, and always only standard input and standard output. */
class FfmpegCommandTest {
    private fun inputFormat(c: List<String>) = c[c.indexOf("-f") + 1]

    @Test
    fun theInputFormatFollowsTheCodec() {
        assertEquals("h264", inputFormat(ffmpegCommand("ffmpeg", VideoCodec.H264, 960, 12)))
        assertEquals("hevc", inputFormat(ffmpegCommand("ffmpeg", VideoCodec.H265, 960, 12)))
    }

    @Test
    fun theProbeIsSmallSoThePictureAppearsWithinASecondOrTwo() {
        for (codec in VideoCodec.entries) {
            val c = ffmpegCommand("ffmpeg", codec, 960, 12)
            assertEquals("32768", c[c.indexOf("-probesize") + 1])
            assertEquals("0", c[c.indexOf("-analyzeduration") + 1])
            // Input options only count before the input.
            assertTrue(c.indexOf("-probesize") < c.indexOf("-i") && c.indexOf("-analyzeduration") < c.indexOf("-i"))
        }
    }

    @Test
    fun theDecoderOnlyEverReadsStandardInputAndWritesStandardOutput() {
        for (codec in VideoCodec.entries) {
            val c = ffmpegCommand("/x/ffmpeg", codec, 640, 10)
            assertEquals("/x/ffmpeg", c.first())
            assertEquals("pipe", c[c.indexOf("-protocol_whitelist") + 1])
            assertEquals("pipe:0", c[c.indexOf("-i") + 1])
            assertEquals("pipe:1", c.last())
            assertEquals(1, c.count { it == "-i" })
            assertFalse(c.any { it.startsWith("rtsp") || it.startsWith("http") || it.startsWith("tcp") || it.startsWith("udp") }, c.toString())
            assertTrue("scale='min(640,iw)':-2,fps=10" in c)
        }
    }
}
