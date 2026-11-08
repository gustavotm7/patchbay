package dev.patchbay.windows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.media.CameraVideo
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.screens.CameraPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * Camera video on Windows (ADR 0012): Patchbay's own guarded RTSP client feeds H.264 to a local ffmpeg, which hands back JPEG pictures.
 * ffmpeg is not shipped; without it the panel says where to put it.
 */
class DesktopCameraPlayer(private val video: CameraVideo) : CameraPlayer {
    @Composable
    override fun Render(lease: StreamLease, modifier: Modifier) {
        val c = Patchbay.colors
        var frame by remember(lease) { mutableStateOf<ImageBitmap?>(null) }
        var message by remember(lease) { mutableStateOf<String?>(null) }
        LaunchedEffect(lease) {
            val result = withContext(Dispatchers.IO) {
                video.play(lease) { jpeg ->
                    try {
                        frame = SkiaImage.makeFromEncoded(jpeg).toComposeImageBitmap()
                    } catch (_: Exception) {
                        // A damaged picture is skipped; the next one replaces it.
                    }
                }
            }
            if (result is PatchbayResult.Err && result.error !is PatchbayError.Cancelled) message = result.error.message
        }
        Box(modifier.background(c.surface), contentAlignment = Alignment.Center) {
            val f = frame
            if (f != null) Image(f, "Camera picture", Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            else PText(message ?: "Connecting…", Patchbay.type.body, c.textSecondary, Modifier.padding(Space.m))
        }
    }
}
