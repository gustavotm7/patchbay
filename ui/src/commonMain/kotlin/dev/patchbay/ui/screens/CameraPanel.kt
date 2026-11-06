package dev.patchbay.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.launch

/**
 * A camera with a local stream: status and where the stream is. Video playback isn't in the app yet; the stream
 * address is shown **without** credentials (they stay in the encrypted store) for use in a media player.
 */
@Composable
fun CameraPanel(
    live: LiveDevice,
    onPair: () -> Unit,
    onWatch: suspend () -> PatchbayResult<StreamLease>,
    modifier: Modifier = Modifier,
) {
    val c = Patchbay.colors
    val s = live.snapshot
    val player = LocalCameraPlayer.current
    val scope = rememberCoroutineScope()
    var lease by remember { mutableStateOf<StreamLease?>(null) }
    var watchError by remember { mutableStateOf<String?>(null) }
    // The login-bearing lease lives only while the picture is on screen.
    DisposableEffect(Unit) { onDispose { lease = null } }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(live.device.name, Patchbay.type.title)
                PText(listOfNotNull(live.device.vendor, live.address).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            when {
                live.needsPairing -> StatusTag("Needs login", Tone.ATTENTION)
                s?.reachability == Reachability.OFFLINE -> StatusTag("Offline", Tone.ALARM)
                s?.reachability == Reachability.DEGRADED -> StatusTag("No reply", Tone.ATTENTION)
                s?.reachability == Reachability.ONLINE -> StatusTag("Stream up", Tone.ACTIVE)
                else -> StatusTag("Checking", Tone.NEUTRAL)
            }
        }
        VSpace(Space.l)
        if (live.needsPairing) {
            PText("Enter the RTSP username and password you set for this camera in the eufy app.", Patchbay.type.body, c.textSecondary)
            VSpace(Space.s)
            PButton("Enter login", onPair)
        } else {
            if (player != null && s?.reachability == Reachability.ONLINE) {
                val active = lease
                if (active != null) {
                    player.Render(active, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    VSpace(Space.s)
                    PButton("Stop", { lease = null })
                } else {
                    PButton(
                        "Watch",
                        {
                            scope.launch {
                                when (val r = onWatch()) {
                                    is PatchbayResult.Ok -> { watchError = null; lease = r.value }
                                    is PatchbayResult.Err -> watchError = r.error.message
                                }
                            }
                        },
                    )
                }
                watchError?.let {
                    VSpace(Space.xs)
                    PText(it, Patchbay.type.caption, c.alarm)
                }
                VSpace(Space.m)
            }
            Legend("Stream")
            VSpace(Space.xs)
            PText("rtsp://${live.address ?: "?"}/live0", Patchbay.type.mono, c.text)
            VSpace(Space.xs)
            PText(
                if (player != null) "Watch plays it here over TCP. To use another player, sign in with the camera's RTSP login. " else "Open this in a media player and sign in with the camera's RTSP login. In-app viewing isn't on this platform yet. " +
                    "RTSP is unencrypted on your Wi-Fi, so keep cameras on a network you trust.",
                Patchbay.type.caption, c.textSecondary,
            )
        }
    }
}
