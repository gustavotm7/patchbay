package dev.patchbay.ui.screens

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.patchbay.core.home.ExposureReport
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.home.LiveHome
import dev.patchbay.core.home.ScanState
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.DeviceGlyph
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.HSpace
import dev.patchbay.ui.designsystem.Hairline
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.PToggle
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Segmented
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.launch

// A device page: its controls by kind, then name, room, adaptive lighting and removal.

/** A device's page: its controls, then which room it is in. */
@Composable
fun LiveDetail(live: LiveHome, id: DeviceId, modifier: Modifier = Modifier) {
    Column(modifier) {
        LiveDetailBody(live, id, Modifier)
        VSpace(Space.l)
        AdaptivePanel(live, id)
        NamePanel(live, id)
        VSpace(Space.l)
        RoomPanel(live, id)
        VSpace(Space.l)
        RemovePanel(live, id)
    }
}

/** The controls for a live device: lights get the LightPanel; anything else a summary. */
@Composable
internal fun LiveDetailBody(live: LiveHome, id: DeviceId, modifier: Modifier = Modifier) {
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val d = devices.firstOrNull { it.device.id == id } ?: return
    val send: (dev.patchbay.core.model.Command) -> Unit = { cmd -> scope.launch { live.send(id, cmd) } }
    if (d.trustChanged) {
        TrustAlert(d, modifier)
    } else if (d.snapshot?.reachability == Reachability.OFFLINE && !d.needsPairing) {
        OfflineNotice(d, modifier)
    } else if (d.device.kind == DeviceKind.LIGHT) {
        LightPanel(d, onCommand = send, modifier = modifier)
    } else if (d.device.capability<dev.patchbay.core.model.Capability.RemoteKeys>() != null) {
        RemotePanel(d, onCommand = send, modifier = modifier)
    } else if (d.device.kind == DeviceKind.TV || d.device.kind == DeviceKind.MEDIA_RECEIVER || d.device.kind == DeviceKind.SPEAKER) {
        TvPanel(d, onCommand = send, modifier = modifier)
    } else if (d.device.kind == DeviceKind.PRINTER_3D) {
        val snap = d.snapshot
        if (snap == null || snap.state<CapabilityState.PrintJobState>() == null) {
            Column(modifier) {
                PText(d.device.name, Patchbay.type.title)
                PText(if (d.needsPairing) "Pair to start monitoring." else "Waiting for the first report…", Patchbay.type.body, Patchbay.colors.textSecondary)
            }
        } else {
            PrinterPanel(
                d.device, snap, dev.patchbay.core.model.TrendSource { _, _ -> null }, roomName = null, modifier = modifier,
                onPrintAction = { send(dev.patchbay.core.model.Command.ControlPrint(it)) },
                onLight = { send(dev.patchbay.core.model.Command.SetAuxLight("Chamber light", it)) },
            )
        }
    } else if (d.device.kind == DeviceKind.CAMERA) {
        val pairing = remember { PairingDialogState() }
        Column(modifier) {
            PairingPanel(pairing)
            if (pairing.step != null) VSpace(Space.m)
            CameraPanel(
                d,
                onWatch = { live.streamLease(id) },
                onPair = {
                    scope.launch {
                        val r = live.pair(id, pairing)
                        pairing.done((r as? dev.patchbay.core.result.PatchbayResult.Err)?.error?.takeIf { it !is dev.patchbay.core.result.PatchbayError.Cancelled }?.message)
                    }
                },
            )
        }
    } else if (d.device.kind == DeviceKind.THERMOSTAT) {
        val pairing = remember { PairingDialogState() }
        val snap = d.snapshot
        Column(modifier) {
            PairingPanel(pairing)
            if (pairing.step != null) VSpace(Space.m)
            if (d.needsPairing || snap == null || snap.state<CapabilityState.ThermostatState>() == null) {
                PText(d.device.name, Patchbay.type.title)
                PText(
                    if (d.needsPairing) "Enter the setup code from the thermostat (Settings, HomeKit). If it is in Apple Home, remove it there first." else "Waiting for the first reading…",
                    Patchbay.type.body, Patchbay.colors.textSecondary,
                )
                if (d.needsPairing) {
                    VSpace(Space.m)
                    PButton("Pair with setup code", {
                        scope.launch {
                            val r = live.pair(id, pairing)
                            pairing.done((r as? dev.patchbay.core.result.PatchbayResult.Err)?.error?.takeIf { it !is dev.patchbay.core.result.PatchbayError.Cancelled }?.message)
                        }
                    })
                }
            } else {
                ThermostatPanel(
                    d.device, snap, dev.patchbay.core.model.TrendSource { _, _ -> null }, roomName = null,
                    onMode = { send(dev.patchbay.core.model.Command.SetHvacMode(it)) },
                    onSetpoints = SetpointListener { h, k -> send(dev.patchbay.core.model.Command.SetSetpoints(h, k)) },
                )
            }
        }
    } else if (d.device.kind == DeviceKind.PLUG || d.device.capability<dev.patchbay.core.model.Capability.Power>() != null) {
        PlugPanel(d, onCommand = send, modifier = modifier)
    } else if (d.device.kind == DeviceKind.APPLIANCE) {
        DataPointsPanel(live, d, modifier)
    } else {
        Column(modifier) { PText(d.device.name, Patchbay.type.title) }
    }
}

/** A device in read-only "learn" mode: pair with its local key, then list the data points it reports so the right controls can be built for it. */
@Composable
internal fun DataPointsPanel(live: LiveHome, d: LiveDevice, modifier: Modifier) {
    val c = Patchbay.colors
    val scope = rememberCoroutineScope()
    val pairing = remember { PairingDialogState() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val values = d.snapshot?.state<CapabilityState.DiagnosticValues>()
    Column(modifier) {
        PairingPanel(pairing)
        if (pairing.step != null) VSpace(Space.m)
        PText(d.device.name, Patchbay.type.title)
        PText("Read-only: Patchbay shows what this device reports and sends it nothing.", Patchbay.type.caption, c.textSecondary)
        VSpace(Space.m)
        if (d.needsPairing) {
            PText("Enter the device's 16-character local key (see docs/TUYA_KEYS.md).", Patchbay.type.body, c.textSecondary)
            VSpace(Space.s)
            PButton("Enter local key", {
                scope.launch {
                    val r = live.pair(d.device.id, pairing)
                    pairing.done((r as? dev.patchbay.core.result.PatchbayResult.Err)?.error?.takeIf { it !is dev.patchbay.core.result.PatchbayError.Cancelled }?.message)
                }
            })
        } else if (values == null) {
            PText("Waiting for the first reading…", Patchbay.type.body, c.textSecondary)
        } else {
            Panel(Modifier.fillMaxWidth(), padding = Space.m) {
                Legend(values.title)
                VSpace(Space.xs)
                values.values.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        PText("DP $k", Patchbay.type.mono, c.textSecondary, Modifier.width(72.dp))
                        PText(v, Patchbay.type.mono, c.text)
                    }
                }
            }
            VSpace(Space.s)
            PButton(if (copied) "Copied" else "Copy data points", {
                clipboard.setText(AnnotatedString(values.values.entries.joinToString("\n") { (k, v) -> "DP $k = $v" }))
                copied = true
            })
        }
    }
}

/** Which room this device is in: pick an existing room, none, or type a new one. */
@Composable
internal fun RoomPanel(live: LiveHome, id: DeviceId) {
    val c = Patchbay.colors
    val rooms by live.rooms.collectAsState()
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    var newRoom by remember { mutableStateOf("") }
    val current = devices.firstOrNull { it.device.id == id }?.device?.roomId?.value
    Panel(Modifier.fillMaxWidth()) {
        Legend("Room")
        VSpace(Space.s)
        (listOf<String?>(null) + rooms).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { r ->
                    PButton(r ?: "None", { scope.launch { live.assignRoom(id, r) } }, Modifier.weight(1f), if (r == current) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
            VSpace(Space.s)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            BasicTextField(
                value = newRoom,
                onValueChange = { v -> newRoom = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(24) },
                singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.weight(1f).border(Space.hair, c.outline, Shapes.control).padding(Space.m),
            )
            PButton("Add room", { scope.launch { live.assignRoom(id, newRoom); newRoom = "" } }, enabled = newRoom.isNotBlank())
        }
        VSpace(Space.xs)
        PText("A new room is created the first time a device is put in it.", Patchbay.type.caption, c.textTertiary)
    }
}

/** The loud one (ADR 0005): the device is not who you paired. No controls are shown. */
@Composable
internal fun TrustAlert(d: LiveDevice, modifier: Modifier) {
    val c = Patchbay.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(d.device.name, Patchbay.type.title)
                PText(listOfNotNull(d.device.vendor, d.address).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            StatusTag("Security alert", Tone.ALARM)
        }
        VSpace(Space.l)
        Panel(Modifier.fillMaxWidth()) {
            PText("This device is presenting a different security key.", Patchbay.type.bodyStrong, c.alarm)
            VSpace(Space.xs)
            PText(
                "Patchbay remembers the key this device showed when you paired it, and it has stopped talking to it. A firmware update or a factory reset can change the key. " +
                    "So can another device on your network pretending to be this one. If you know why it changed, remove it below and pair it again; you'll see the new fingerprint. If you don't, leave it alone.",
                Patchbay.type.body, c.textSecondary,
            )
        }
    }
}

/** No dead buttons: when a device doesn't answer, say so instead of showing controls that do nothing. */
@Composable
internal fun OfflineNotice(d: LiveDevice, modifier: Modifier) {
    val c = Patchbay.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(d.device.name, Patchbay.type.title)
                PText(listOfNotNull(d.device.vendor, d.device.model, d.address).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            StatusTag("Offline", Tone.ALARM)
        }
        VSpace(Space.l)
        Panel(Modifier.fillMaxWidth()) {
            PText("No reply from this device.", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            PText("It may be switched off, unplugged, or on another network. Patchbay keeps trying, so it comes back by itself when it answers.", Patchbay.type.body, c.textSecondary)
        }
    }
}

/** Forget a device and its saved pairing. Two taps, because it can't be undone (it can be found and paired again). */
@Composable
internal fun RemovePanel(live: LiveHome, id: DeviceId) {
    val c = Patchbay.colors
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Legend("Remove device")
        VSpace(Space.xs)
        PText("Forgets this device and its saved pairing on this device. It comes back on the next scan, unpaired.", Patchbay.type.caption, c.textSecondary)
        VSpace(Space.s)
        if (!confirming) {
            PButton("Remove…", { confirming = true })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton("Confirm remove", { scope.launch { live.forget(id) } }, Modifier.weight(1f), ButtonKind.DESTRUCTIVE)
                PButton("Cancel", { confirming = false }, Modifier.weight(1f))
            }
        }
    }
}

/** Adaptive lighting for a light with a white colour temperature: cool at midday, warm in the evening, never overriding a change you make. */
@Composable
internal fun AdaptivePanel(live: LiveHome, id: DeviceId) {
    val c = Patchbay.colors
    val devices by live.devices.collectAsState()
    val adaptive by live.adaptive.collectAsState()
    val scope = rememberCoroutineScope()
    val d = devices.firstOrNull { it.device.id == id } ?: return
    if (d.device.kind != DeviceKind.LIGHT || d.device.capability<dev.patchbay.core.model.Capability.ColourTemperature>() == null) return
    Panel(Modifier.fillMaxWidth()) {
        Legend("Adaptive lighting")
        VSpace(Space.xs)
        PToggle("Follow the day", id in adaptive, { on -> scope.launch { live.setAdaptive(id, on) } })
        VSpace(Space.xs)
        PText(
            "Moves the white colour through the day: cool at midday, warm in the evening. Brightness and colours are never touched. If you change the colour yourself it stops until you switch the light off and on. Runs only while Patchbay is open or in the tray.",
            Patchbay.type.caption, c.textSecondary,
        )
    }
    VSpace(Space.l)
}

/** Give a device a name of your own, and pin it to the top of the Devices tab. */
@Composable
internal fun NamePanel(live: LiveHome, id: DeviceId) {
    val c = Patchbay.colors
    val devices by live.devices.collectAsState()
    val favs by live.favourites.collectAsState()
    val scope = rememberCoroutineScope()
    val current = devices.firstOrNull { it.device.id == id }?.device?.name ?: return
    var text by remember(id, current) { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        Legend("Name")
        VSpace(Space.s)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            BasicTextField(
                value = text, onValueChange = { v -> text = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(32) }, singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.weight(1f).border(Space.hair, c.outline, Shapes.control).padding(Space.m),
            )
            PButton("Save", {
                scope.launch {
                    error = (live.rename(id, text) as? dev.patchbay.core.result.PatchbayResult.Err)?.error?.message
                }
            }, enabled = text.isNotBlank() && text.trim() != current)
        }
        error?.let {
            VSpace(Space.xs)
            PText(it, Patchbay.type.caption, c.alarm)
        }
        VSpace(Space.xs)
        PToggle("Show in Favourites", id in favs, { on -> scope.launch { live.setFavourite(id, on) } })
    }
    VSpace(Space.l)
}
