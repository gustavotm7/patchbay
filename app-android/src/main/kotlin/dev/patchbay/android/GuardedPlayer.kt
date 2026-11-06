package dev.patchbay.android

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.net.GuardedSocketFactory
import dev.patchbay.core.net.NetGuard
import dev.patchbay.ui.screens.CameraPlayer

/**
 * In-app camera playback (ADR 0008). The player never opens its own connection: RTSP runs over the single TCP
 * connection made by a [GuardedSocketFactory] (LAN addresses only, no DNS, no proxy), with media interleaved on it
 * (`setForceUseRtpTcp`) because UDP media sockets could not be guarded. This is the only Android file allowed to
 * name a SocketFactory (tools/check-policy.sh). It makes no sockets itself.
 */
@OptIn(UnstableApi::class)
class GuardedPlayer(private val netGuard: NetGuard) : CameraPlayer {

    @Composable
    override fun Render(lease: StreamLease, modifier: Modifier) {
        val context = LocalContext.current
        val player = remember(lease) {
            ExoPlayer.Builder(context).build().apply {
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true)
                    .setSocketFactory(GuardedSocketFactory(netGuard))
                    .createMediaSource(MediaItem.fromUri(lease.url))
                setMediaSource(source)
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(
            factory = { PlayerView(it).apply { useController = false } },
            update = { it.player = player },
            modifier = modifier,
        )
    }
}
