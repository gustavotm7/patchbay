package dev.patchbay.core.media

import dev.patchbay.core.adapter.RecentProblems
import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.adapter.rtsp.MjpegSplitter
import dev.patchbay.core.adapter.rtsp.RtspStreamClient
import dev.patchbay.core.adapter.rtsp.VideoCodec
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

/** Finds an `ffmpeg` executable: first in the folders given (the app's own folder), then on PATH. Only a plain file named ffmpeg is accepted. */
public object FfmpegLocator {
    public fun find(extraDirs: List<File>, path: String? = System.getenv("PATH"), windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")): File? {
        val names = if (windows) listOf("ffmpeg.exe") else listOf("ffmpeg")
        val dirs = extraDirs + extraDirs.map { File(it, "ffmpeg") } + path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
        for (d in dirs) for (n in names) {
            val f = File(d, n)
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }
}

/** The `ffmpeg` command line that decodes an Annex-B [codec] stream on standard input into JPEGs on standard output (and nothing else: no files, no network). */
internal fun ffmpegCommand(executable: String, codec: VideoCodec, maxWidth: Int, framesPerSecond: Int): List<String> = listOf(
    executable, "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay",
    // ffmpeg's default probing waits for 5 MB or 5 seconds of a raw stream before it shows anything (measured: about 4 s to the first picture of a live H.265 stream and none
    // within 4 s for H.264). The parameter sets are at the start of the stream we feed it (or arrive with the next keyframe), so a small probe is enough: about a second.
    "-probesize", "32768", "-analyzeduration", "0",
    "-protocol_whitelist", "pipe", "-f", if (codec == VideoCodec.H265) "hevc" else "h264", "-i", "pipe:0", "-an",
    "-vf", "scale='min($maxWidth,iw)':-2,fps=$framesPerSecond", "-q:v", "6", "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1",
)

/**
 * Turns H.264 or H.265 into JPEG pictures by piping it through a local `ffmpeg` process (ADR 0012): `ffmpeg` reads **only** standard input
 * (`-protocol_whitelist pipe`), so it never opens a network connection; Patchbay's own guarded RTSP client does all the networking.
 * Frames come out of standard output and are split on JPEG markers.
 */
public class VideoDecoder private constructor(private val command: List<String>) : AutoCloseable {
    public constructor(ffmpeg: File, maxWidth: Int = 960, framesPerSecond: Int = 12, codec: VideoCodec = VideoCodec.H264) : this(
        ffmpegCommand(ffmpeg.absolutePath, codec, maxWidth, framesPerSecond),
    )

    public companion object {
        /** Any program that reads video on standard input and writes concatenated JPEGs to standard output (used by tests with a stand-in). */
        public fun withCommand(command: List<String>): VideoDecoder = VideoDecoder(command)
    }

    private var process: Process? = null
    private var stdin: OutputStream? = null
    @Volatile private var ended = false

    /** Starts the process; [onFrame] runs on a reader thread for each JPEG. */
    public fun start(onFrame: (ByteArray) -> Unit) {
        val p = ProcessBuilder(command).start()
        thread(isDaemon = true, name = "video-decoder-err") { try { p.errorStream.use { e -> val b = ByteArray(4096); while (e.read(b) >= 0) { } } } catch (_: IOException) {} }
        process = p; stdin = p.outputStream
        thread(isDaemon = true, name = "video-decoder-out") {
            val splitter = MjpegSplitter(onFrame)
            val buf = ByteArray(32 * 1024)
            try {
                p.inputStream.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; splitter.push(buf, 0, n) } }
            } catch (_: IOException) {
            } finally { ended = true }
        }
    }

    /** Feeds Annex-B video; throws [IOException] once the decoder has exited. */
    public fun write(nal: ByteArray) {
        val out = stdin ?: throw IOException("decoder not started")
        if (ended) throw IOException("decoder exited")
        out.write(nal); out.flush()
    }

    override fun close() {
        ended = true
        try { stdin?.close() } catch (_: IOException) {}
        process?.destroy()
    }
}

/** Plays a camera [StreamLease] as a series of JPEG pictures: the guarded RTSP client feeds the decoder. */
public class CameraVideo(private val netGuard: NetGuard, private val ffmpeg: File?, private val config: RtspStreamClient.Config = RtspStreamClient.Config()) {
    private class DecoderGone : RuntimeException("decoder exited")

    public val available: Boolean get() = ffmpeg != null

    private val problems = RecentProblems()
    private val started = java.util.concurrent.atomic.AtomicInteger()
    private val framesShown = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var lastCodec: VideoCodec? = null

    /** Facts for the diagnostics report: whether a decoder was found, how many streams were tried, which codec the last one used, how many pictures arrived, and why the last ones stopped. Never an address or a login. */
    /**
     * Where the program came from. One found through PATH is whatever program of that name comes first there, so it is said out loud: a copy beside Patchbay is the
     * one the owner chose (review finding F-M8).
     */
    private fun ffmpegSource(): String {
        val f = ffmpeg ?: return ""
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { it.isNotBlank() && File(it).absoluteFile == f.absoluteFile.parentFile }
        return if (onPath) " (from PATH, not beside Patchbay)" else " (beside Patchbay)"
    }

    public fun diagnostics(): List<String> =
        listOf("ffmpeg found: ${ffmpeg != null}${ffmpegSource()}; streams started: ${started.get()}; codec of the last stream: ${lastCodec?.label ?: "none yet"}; pictures decoded: ${framesShown.get()}") +
            problems.list().map { "problem: $it" }

    /** Runs until cancelled or the stream ends. [onFrame] gets JPEG bytes at the decoder's frame rate. */
    public suspend fun play(lease: StreamLease, onFrame: (ByteArray) -> Unit): PatchbayResult<Unit> {
        val exe = ffmpeg ?: return PatchbayResult.Err(PatchbayError.Unsupported("Video needs ffmpeg. Put ffmpeg.exe in the Patchbay folder (see the camera help).")).also { problems.add("no ffmpeg found") }
        // The decoder is started once the camera has said which codec it sends (H.264 and H.265 need different ffmpeg input formats).
        return playWith(lease, { codec -> VideoDecoder(exe, codec = codec) }, onFrame)
    }

    /** Plays with a decoder you made (tests use a stand-in program); it is started before the camera is contacted and told nothing about the codec. */
    public suspend fun play(lease: StreamLease, decoder: VideoDecoder, onFrame: (ByteArray) -> Unit): PatchbayResult<Unit> {
        started.incrementAndGet()
        try {
            decoder.start { jpeg -> framesShown.incrementAndGet(); onFrame(jpeg) }
        } catch (e: IOException) {
            problems.add("could not start ffmpeg: ${e.javaClass.simpleName}")
            return PatchbayResult.Err(PatchbayError.Unsupported("Couldn't start ffmpeg: ${e.javaClass.simpleName}"))
        }
        // It was started before the camera was contacted, so it is closed here even if the stream never got as far as using it.
        try { return stream(lease, { decoder }, onFrame, alreadyStarted = true) } finally { decoder.close() }
    }

    private suspend fun playWith(lease: StreamLease, decoderFor: (VideoCodec) -> VideoDecoder, onFrame: (ByteArray) -> Unit): PatchbayResult<Unit> {
        started.incrementAndGet()
        return stream(lease, decoderFor, onFrame, alreadyStarted = false)
    }

    private class DecoderStartFailed(val reason: IOException) : RuntimeException("decoder did not start")

    private suspend fun stream(lease: StreamLease, decoderFor: (VideoCodec) -> VideoDecoder, onFrame: (ByteArray) -> Unit, alreadyStarted: Boolean): PatchbayResult<Unit> {
        var decoder: VideoDecoder? = null
        val result = try {
            RtspStreamClient(netGuard, config).stream(
                lease,
                onCodec = { codec ->
                    lastCodec = codec
                    val d = decoderFor(codec)
                    if (!alreadyStarted) {
                        try { d.start { jpeg -> framesShown.incrementAndGet(); onFrame(jpeg) } } catch (e: IOException) { throw DecoderStartFailed(e) }
                    }
                    decoder = d
                },
                onNal = { nal -> try { (decoder ?: throw DecoderGone()).write(nal) } catch (e: IOException) { throw DecoderGone() } },
            )
        } catch (e: DecoderGone) {
            PatchbayResult.Err(PatchbayError.Unsupported("ffmpeg stopped. Check that this is a working ffmpeg build."))
        } catch (e: DecoderStartFailed) {
            problems.add("could not start ffmpeg: ${e.reason.javaClass.simpleName}")
            PatchbayResult.Err(PatchbayError.Unsupported("Couldn't start ffmpeg: ${e.reason.javaClass.simpleName}"))
        } finally {
            decoder?.close()
        }
        // Fixed-shape reasons only ("the camera offers no H.264 or H.265 video...", "the camera stopped sending"); stopping on purpose is not a problem.
        if (result is PatchbayResult.Err && result.error !is PatchbayError.Cancelled) problems.add(result.error.message.filter { it.code in 32..126 }.take(120))
        return result
    }
}
