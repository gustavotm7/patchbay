package dev.patchbay.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.VSpace

/**
 * A camera with a local stream: status and where the stream is. Video playback isn't in the app yet; the stream
 * address is shown **without** credentials (they stay in the encrypted store) for use in a media player.
 */
@Composable
fun CameraPanel(live: LiveDevice, onPair: () -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val s = live.snapshot
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
            Legend("Stream")
            VSpace(Space.xs)
            PText("rtsp://${live.address ?: "?"}/live0", Patchbay.type.mono, c.text)
            VSpace(Space.xs)
            PText(
                "Open this in a media player and sign in with the camera's RTSP login. In-app viewing is coming. " +
                    "RTSP is unencrypted on your Wi-Fi, so keep cameras on a network you trust.",
                Patchbay.type.caption, c.textSecondary,
            )
        }
    }
}
