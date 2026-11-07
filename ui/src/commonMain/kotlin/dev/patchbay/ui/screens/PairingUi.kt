package dev.patchbay.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.MediaAction
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.PToggle
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Readout
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.CompletableDeferred

/**
 * Bridges an adapter's suspend [PairingPrompt] to the UI. Each step is shown inline in [PairingPanel]
 * until the user answers. Codes are masked.
 */
class PairingDialogState : PairingPrompt {
    sealed interface Step {
        class Fingerprint(val deviceName: String, val fingerprint: String, val reply: CompletableDeferred<Boolean>) : Step
        class Code(val label: String, val length: IntRange, val reply: CompletableDeferred<String?>) : Step
        class Instruction(val message: String) : Step
        class Failed(val message: String) : Step
    }

    var step: Step? by mutableStateOf(null)
        private set

    override suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        step = Step.Fingerprint(deviceName, sha256, d)
        return d.await()
    }

    override suspend fun askForCode(label: String, length: IntRange): String? {
        val d = CompletableDeferred<String?>()
        step = Step.Code(label, length, d)
        return d.await()
    }

    override suspend fun instruct(message: String) {
        step = Step.Instruction(message)
    }

    fun done(error: String?) {
        step = error?.let { Step.Failed(it) }
    }

    fun dismiss() {
        (step as? Step.Fingerprint)?.reply?.complete(false)
        (step as? Step.Code)?.reply?.complete(null)
        step = null
    }
}

@Composable
fun PairingPanel(state: PairingDialogState, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val step = state.step ?: return
    Panel(modifier.fillMaxWidth().border(Space.hair, c.text, Shapes.panel), padding = Space.m) {
        Legend("Pairing")
        VSpace(Space.s)
        when (step) {
            is PairingDialogState.Step.Fingerprint -> {
                PText("Check ${step.deviceName}'s key", Patchbay.type.bodyStrong)
                VSpace(Space.xs)
                PText(step.fingerprint, Patchbay.type.mono, c.text)
                VSpace(Space.xs)
                PText(
                    "This fingerprint identifies the device. Patchbay remembers it and will warn you if it ever changes. " +
                        "Trust it only if you're pairing your own device on your own network.",
                    Patchbay.type.caption, c.textSecondary,
                )
                VSpace(Space.s)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PButton("Trust", { step.reply.complete(true) }, Modifier.weight(1f), ButtonKind.PRIMARY)
                    PButton("Cancel", { state.dismiss() }, Modifier.weight(1f))
                }
            }
            is PairingDialogState.Step.Code -> {
                var text by remember(step) { mutableStateOf("") }
                PText(step.label, Patchbay.type.bodyStrong)
                VSpace(Space.xs)
                BasicTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { !it.isISOControl() && !it.isWhitespace() }.take(step.length.last) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = Patchbay.type.valueM.copy(color = c.text),
                    modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.s),
                )
                VSpace(Space.s)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    PButton("OK", { step.reply.complete(text) }, Modifier.weight(1f), ButtonKind.PRIMARY, enabled = text.length in step.length)
                    PButton("Cancel", { state.dismiss() }, Modifier.weight(1f))
                }
            }
            is PairingDialogState.Step.Instruction -> {
                PText(step.message, Patchbay.type.bodyStrong)
                VSpace(Space.xs)
                PText("Waiting for the device…", Patchbay.type.caption, c.textSecondary)
            }
            is PairingDialogState.Step.Failed -> {
                PText(step.message, Patchbay.type.body, c.alarm)
                VSpace(Space.s)
                PButton("Close", { state.dismiss() })
            }
        }
    }
}

/** TV, Chromecast and speaker controls rendered from capabilities: power, volume, mute, inputs, transport, and what's playing. */
@Composable
fun TvPanel(live: LiveDevice, onCommand: (Command) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val s = live.snapshot
    val on = s?.state<CapabilityState.PowerState>()?.on ?: false
    val vol = s?.state<CapabilityState.VolumeState>()
    val media = s?.state<CapabilityState.MediaState>()
    val hasPower = live.device.capability<dev.patchbay.core.model.Capability.Power>() != null
    val hasTransport = live.device.capability<dev.patchbay.core.model.Capability.MediaTransport>() != null
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(live.device.name, Patchbay.type.title)
                PText(listOfNotNull(live.device.vendor, live.device.model, live.address).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            when {
                live.needsPairing -> StatusTag("Needs pairing", Tone.ATTENTION)
                s?.reachability == Reachability.OFFLINE -> StatusTag("Offline", Tone.ALARM)
                s?.reachability == Reachability.DEGRADED -> StatusTag("No reply", Tone.ATTENTION)
                !hasPower && media?.status == dev.patchbay.core.model.PlaybackStatus.PLAYING -> StatusTag("Playing", Tone.ACTIVE)
                !hasPower && media?.status == dev.patchbay.core.model.PlaybackStatus.PAUSED -> StatusTag("Paused", Tone.NEUTRAL)
                !hasPower -> StatusTag("Online", Tone.NEUTRAL)
                on -> StatusTag("On", Tone.ACTIVE)
                else -> StatusTag("Standby", Tone.NEUTRAL)
            }
        }
        VSpace(Space.l)
        val nowPlaying = media?.title
        if (nowPlaying != null) {
            Legend("Now playing")
            PText(nowPlaying, Patchbay.type.bodyStrong, maxLines = 2)
            media?.subtitle?.let { PText(it, Patchbay.type.caption, c.textSecondary, maxLines = 1) }
            VSpace(Space.l)
        }
        Legend("Volume")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Readout(vol?.level?.toString() ?: "—", "/ 100", Patchbay.type.valueL, spokenUnit = "of 100", label = "Volume")
        }
        VSpace(Space.s)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PButton("−5", { vol?.let { onCommand(Command.SetVolume((it.level - 5).coerceAtLeast(0))) } }, Modifier.weight(1f), enabled = vol != null)
            PButton("−1", { vol?.let { onCommand(Command.SetVolume((it.level - 1).coerceAtLeast(0))) } }, Modifier.weight(1f), enabled = vol != null)
            PButton("+1", { vol?.let { onCommand(Command.SetVolume((it.level + 1).coerceAtMost(100))) } }, Modifier.weight(1f), enabled = vol != null)
            PButton("+5", { vol?.let { onCommand(Command.SetVolume((it.level + 5).coerceAtMost(100))) } }, Modifier.weight(1f), enabled = vol != null)
        }
        PToggle("Mute", vol?.muted ?: false, { onCommand(Command.SetMute(it)) })
        if (hasTransport) {
            VSpace(Space.m)
            Legend("Playback")
            VSpace(Space.xs)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton("Play", { onCommand(Command.Media(MediaAction.PLAY)) }, Modifier.weight(1f))
                PButton("Pause", { onCommand(Command.Media(MediaAction.PAUSE)) }, Modifier.weight(1f))
                PButton("Stop", { onCommand(Command.Media(MediaAction.STOP)) }, Modifier.weight(1f))
            }
        }
        val inputs = live.device.capability<dev.patchbay.core.model.Capability.InputSource>()?.sources.orEmpty()
        if (inputs.isNotEmpty()) {
            VSpace(Space.m)
            Legend("Input")
            VSpace(Space.xs)
            inputs.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { PButton(it, { onCommand(Command.SelectInput(it)) }, Modifier.weight(1f)) }
                }
                VSpace(Space.xs)
            }
        }
        if (hasPower) {
            VSpace(Space.m)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton("Turn on", { onCommand(Command.SetPower(true)) }, Modifier.weight(1f), ButtonKind.PRIMARY, enabled = !on)
                PButton("Turn off", { onCommand(Command.SetPower(false)) }, Modifier.weight(1f), ButtonKind.DESTRUCTIVE, enabled = on)
            }
            VSpace(Space.xs)
            PText(
                "Turn on uses Wake-on-LAN: enable “Turn on via Wi-Fi” on the TV. Patchbay must have reached the TV once this session.",
                Patchbay.type.caption, c.textTertiary,
            )
        }
    }
}
