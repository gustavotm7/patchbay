package dev.patchbay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.VSpace

/** Power-only control for plugs and any other device whose only controllable capability is Power. */
@Composable
fun PlugPanel(live: LiveDevice, onCommand: (Command) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val s = live.snapshot
    val on = s?.state<CapabilityState.PowerState>()?.on ?: false
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(live.device.name, Patchbay.type.title)
                PText(listOfNotNull(live.device.vendor, live.device.model, live.address).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            when {
                s?.reachability == Reachability.OFFLINE -> StatusTag("Offline", Tone.ALARM)
                s?.reachability == Reachability.DEGRADED -> StatusTag("No reply", Tone.ATTENTION)
                s == null -> StatusTag("Waiting", Tone.NEUTRAL)
                on -> StatusTag("On", Tone.ACTIVE)
                else -> StatusTag("Off", Tone.NEUTRAL)
            }
        }
        VSpace(Space.l)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PButton("Turn on", { onCommand(Command.SetPower(true)) }, Modifier.weight(1f), ButtonKind.PRIMARY, enabled = s != null && !on)
            PButton("Turn off", { onCommand(Command.SetPower(false)) }, Modifier.weight(1f), ButtonKind.DESTRUCTIVE, enabled = s != null && on)
        }
    }
}
