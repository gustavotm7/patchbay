package dev.patchbay.core.media

import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.adapter.rtsp.MjpegSplitter
import dev.patchbay.core.adapter.rtsp.RtspStreamClient
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

/**
 * Turns H.264 into JPEG pictures by piping it through a local `ffmpeg` process (ADR 0012): `ffmpeg` reads **only** standard input
 * (`-protocol_whitelist pipe`), so it never opens a network connection; Patchbay's own guarded RTSP client does all the networking.
 * Frames come out of standard output and are split on JPEG markers.
 */
public class VideoDecoder private constructor(private val command: List<String>) : AutoCloseable {
    public constructor(ffmpeg: File, maxWidth: Int = 960, framesPerSecond: Int = 12) : this(
        listOf(
            ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-flags", "low_delay",
            "-protocol_whitelist", "pipe", "-f", "h264", "-i", "pipe:0", "-an",
            "-vf", "scale='min($maxWidth,iw)':-2,fps=$framesPerSecond", "-q:v", "6", "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1",
        ),
    )

    public companion object {
        /** Any program that reads H.264 on standard input and writes concatenated JPEGs to standard output (used by tests with a stand-in). */
        public fun withCommand(command: List<String>): VideoDecoder = VideoDecoder(command)
    }

    private var process: Process? = null
    private var stdin: OutputStream? = null
    @Volatile private var ended = false

    /** Starts the process; [onFrame] runs on a reader thread for each JPEG. */
    public fun start(onFrame: (ByteArray) -> Unit) {
        val p = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
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

    /** Feeds H.264; throws [IOException] once the decoder has exited. */
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

    /** Runs until cancelled or the stream ends. [onFrame] gets JPEG bytes at the decoder's frame rate. */
    public suspend fun play(lease: StreamLease, onFrame: (ByteArray) -> Unit): PatchbayResult<Unit> {
        val exe = ffmpeg ?: return PatchbayResult.Err(PatchbayError.Unsupported("Video needs ffmpeg. Put ffmpeg.exe in the Patchbay folder (see the camera help)."))
        return play(lease, VideoDecoder(exe), onFrame)
    }

    public suspend fun play(lease: StreamLease, decoder: VideoDecoder, onFrame: (ByteArray) -> Unit): PatchbayResult<Unit> {
        try {
            decoder.start(onFrame)
        } catch (e: IOException) {
            return PatchbayResult.Err(PatchbayError.Unsupported("Couldn't start ffmpeg: ${e.javaClass.simpleName}"))
        }
        return try {
            RtspStreamClient(netGuard, config).stream(lease) { nal -> try { decoder.write(nal) } catch (e: IOException) { throw DecoderGone() } }
        } catch (e: DecoderGone) {
            PatchbayResult.Err(PatchbayError.Unsupported("ffmpeg stopped. Check that this is a working ffmpeg build."))
        } finally {
            decoder.close()
        }
    }
}
