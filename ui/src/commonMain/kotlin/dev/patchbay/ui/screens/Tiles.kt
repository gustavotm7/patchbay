package dev.patchbay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.DeviceGlyph
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Space

/** One line of status for a tile: what the device is doing, or why it can't be reached. */
internal fun tileStatus(d: LiveDevice): String {
    val s = d.snapshot
    if (d.trustChanged) return "Key changed"
    if (d.needsPairing) return "Tap to pair"
    if (s == null) return "Connecting…"
    when (s.reachability) {
        Reachability.OFFLINE -> return "Offline"
        Reachability.DEGRADED -> return "No reply"
        Reachability.NEVER_CONNECTED -> return "Connecting…"
        Reachability.ONLINE -> {}
    }
    s.state<CapabilityState.PrintJobState>()?.let {
        return when (it.stage) {
            PrintStage.PRINTING -> "Printing · ${Fmt.percent(it.progress.value)} %"
            else -> it.stage.name.lowercase().replaceFirstChar(Char::uppercase)
        }
    }
    s.state<CapabilityState.ThermostatState>()?.let { return "${Fmt.celsius(it.current.value)} °C" }
    s.state<CapabilityState.MediaState>()?.let { m ->
        // Chromecasts and speakers have no power state: show what they're doing.
        if (s.state<CapabilityState.PowerState>() == null) {
            return when (m.status) {
                dev.patchbay.core.model.PlaybackStatus.PLAYING -> "Playing" + (m.title?.let { " · $it" } ?: "")
                dev.patchbay.core.model.PlaybackStatus.PAUSED -> "Paused"
                dev.patchbay.core.model.PlaybackStatus.BUFFERING -> "Buffering"
                dev.patchbay.core.model.PlaybackStatus.IDLE -> "Idle"
            }
        }
    }
    s.state<CapabilityState.PowerState>()?.let { p ->
        if (!p.on) return "Off"
        s.state<CapabilityState.BrightnessState>()?.let { return "On · ${Fmt.percent(it.level.value)} %" }
        return "On"
    }
    if (d.device.kind == DeviceKind.CAMERA) return "Stream up"
    if (d.device.capability<Capability.RemoteKeys>() != null) return "Remote ready"
    return "Online"
}

/** Lights and plugs switch on a tap; everything else opens its page (a TV or printer is never one careless tap from turning off). */
internal fun tileToggles(d: LiveDevice): Boolean =
    !d.needsPairing && !d.trustChanged && d.snapshot?.reachability == Reachability.ONLINE && (d.device.kind == DeviceKind.LIGHT || d.device.kind == DeviceKind.PLUG) && d.device.capability<Capability.Power>() != null

/**
 * A device tile: glyph, name, one line of status. "On" is the inverse fill (a light tile with dark text on dark themes),
 * so on and off read at a glance without relying on colour. A tile that toggles has a separate details button.
 */
@Composable
fun DeviceTile(
    d: LiveDevice,
    selected: Boolean,
    onPrimary: () -> Unit,
    onDetails: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = Patchbay.colors
    val s = d.snapshot
    val on = s?.state<CapabilityState.PowerState>()?.on == true && s.reachability == Reachability.ONLINE
    val fill = if (on) c.inverse else c.surface
    val fg = if (on) c.onInverse else c.text
    val status = tileStatus(d)
    val attention = d.needsPairing || s?.reachability == Reachability.OFFLINE || s?.reachability == Reachability.DEGRADED
    val alarm = d.trustChanged
    Column(
        modifier
            .defaultMinSize(minHeight = 120.dp)
            .clip(Shapes.tile)
            .background(fill, Shapes.tile)
            .then(if (selected) Modifier.border(2.dp, c.active, Shapes.tile) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "${d.device.name}, $status"
                if (onDetails != null) stateDescription = if (on) "on" else "off"
            }
            .clickable(role = if (onDetails != null) Role.Switch else Role.Button, onClick = onPrimary)
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(if (on) c.onInverse.copy(alpha = 0.10f) else c.raised, Shapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                DeviceGlyph(
                    d.device.kind, DeviceViews.glyphState(d.device, s), Modifier.size(22.dp),
                    emitted = if (on) null else DeviceViews.emittedColor(d.device, s, c),
                    tint = fg, onFill = fill,
                )
            }
            Box(Modifier.weight(1f))
            if (onDetails != null) {
                Box(
                    Modifier.size(44.dp).clip(Shapes.pill)
                        .semantics { contentDescription = "Details for ${d.device.name}" }
                        .clickable(role = Role.Button, onClick = onDetails),
                    contentAlignment = Alignment.Center,
                ) { PText("›", Patchbay.type.title, if (on) c.onInverse else c.textSecondary) }
            }
        }
        Box(Modifier.weight(1f, fill = true).defaultMinSize(minHeight = Space.l))
        PText(d.device.name, Patchbay.type.bodyStrong, fg, maxLines = 2)
        PText(status, Patchbay.type.caption, if (on) c.onInverse else if (alarm) c.alarm else if (attention) c.attention else c.textSecondary, maxLines = 1)
    }
}

/** Lays tiles out in as many equal columns as fit (two on a phone, more on desktop). */
@Composable
fun <T> TileGrid(items: List<T>, modifier: Modifier = Modifier, minTile: Dp = 150.dp, gap: Dp = Space.m, tile: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = ((maxWidth + gap) / (minTile + gap)).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(cols).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { tile(it, Modifier.weight(1f)) }
                    repeat(cols - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}
