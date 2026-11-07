package dev.patchbay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.model.RemoteKey
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.VSpace

/** A TV remote: d-pad, back and home, play/pause, volume and power. Keys only; nothing is installed, launched or read. */
@Composable
fun RemotePanel(live: LiveDevice, onCommand: (Command) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val s = live.snapshot
    fun key(k: RemoteKey) = { onCommand(Command.PressKey(k)) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(live.device.name, Patchbay.type.title)
                PText(listOfNotNull(live.device.vendor, live.address).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            when {
                live.needsPairing -> StatusTag("Needs pairing", Tone.ATTENTION)
                s?.reachability == Reachability.ONLINE -> StatusTag("Connected", Tone.ACTIVE)
                else -> StatusTag("No reply", Tone.ATTENTION)
            }
        }
        VSpace(Space.l)
        Legend("Navigate")
        VSpace(Space.xs)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { PButton("Up", key(RemoteKey.UP), Modifier.weight(1f)); Box(Modifier.weight(2f)) }
        VSpace(Space.xs)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            PButton("Left", key(RemoteKey.LEFT), Modifier.weight(1f))
            PButton("OK", key(RemoteKey.SELECT), Modifier.weight(1f), ButtonKind.PRIMARY)
            PButton("Right", key(RemoteKey.RIGHT), Modifier.weight(1f))
        }
        VSpace(Space.xs)
        Row(Modifier.fillMaxWidth()) { PButton("Down", key(RemoteKey.DOWN), Modifier.weight(1f)); Box(Modifier.weight(2f)) }
        VSpace(Space.m)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            PButton("Back", key(RemoteKey.BACK), Modifier.weight(1f))
            PButton("Home", key(RemoteKey.HOME), Modifier.weight(1f))
            PButton("Play/Pause", key(RemoteKey.PLAY_PAUSE), Modifier.weight(1f))
        }
        VSpace(Space.m)
        Legend("Volume")
        VSpace(Space.xs)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            PButton("Vol −", key(RemoteKey.VOLUME_DOWN), Modifier.weight(1f))
            PButton("Mute", key(RemoteKey.MUTE), Modifier.weight(1f))
            PButton("Vol +", key(RemoteKey.VOLUME_UP), Modifier.weight(1f))
        }
        VSpace(Space.m)
        PButton("Power", key(RemoteKey.POWER), kind = ButtonKind.DESTRUCTIVE)
        VSpace(Space.xs)
        PText("Power toggles the TV like the power button on its remote.", Patchbay.type.caption, c.textTertiary)
    }
}
