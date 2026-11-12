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
import dev.patchbay.ui.designsystem.animatedColor
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

// The home screen: header and summary, needs-attention list, scene pills, device tiles by room, and the phone/desktop list-and-detail layouts.

/**
 * What a new user sees before anything is found: three numbered steps that tick off as they happen, and what to have ready for the
 * systems that need something first. Nothing here is a wall of text: one primary action (Scan), then plain next steps.
 */
@Composable
internal fun FirstRunPanel(scan: ScanState, scanning: Boolean, onScan: () -> Unit) {
    val c = Patchbay.colors
    val scanned = scan == ScanState.DONE
    Panel(Modifier.fillMaxWidth()) {
        PText(if (scanned) "Nothing answered yet" else "Let's find your devices", Patchbay.type.title)
        VSpace(Space.xs)
        PText(
            if (scanned) "That is common the first time. Check the notes below, then scan again."
            else "Patchbay looks for devices on this Wi-Fi. Nothing leaves your home network.",
            Patchbay.type.body, c.textSecondary,
        )
        VSpace(Space.m)
        FirstRunStep(1, "Scan your network", "Lights, TVs, plugs, thermostats and printers announce themselves.", done = scanned)
        FirstRunStep(2, "Pair what needs it", "Some devices show “Tap to pair”: a code, a button press or an access code, once.", done = false)
        FirstRunStep(3, "Add the quiet ones by address", "Cameras, Tuya bulbs and vacuums don't announce themselves. Use “Add by address” below.", done = false)
        VSpace(Space.m)
        PButton(if (scanning) "Scanning…" else if (scanned) "Scan again" else "Scan now", onScan, kind = ButtonKind.PRIMARY, enabled = !scanning, busy = scanning)
        if (scanned) {
            VSpace(Space.m)
            Legend("Have these ready")
            VSpace(Space.xs)
            PText("Govee: “LAN Control” switched on in the Govee app.", Patchbay.type.caption, c.textSecondary)
            PText("ecobee: removed from Apple Home first; its setup code at hand.", Patchbay.type.caption, c.textSecondary)
            PText("TVs: switched on, on the same Wi-Fi (not a guest network).", Patchbay.type.caption, c.textSecondary)
        }
    }
}

@Composable
private fun FirstRunStep(number: Int, title: String, detail: String, done: Boolean) {
    val c = Patchbay.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).background(animatedColor(if (done) c.active else c.raised), Shapes.pill),
            contentAlignment = Alignment.Center,
        ) { PText(if (done) "✓" else "$number", Patchbay.type.bodyStrong, if (done) c.onSignal else c.textSecondary) }
        HSpace(Space.m)
        Column(Modifier.weight(1f)) {
            PText(title, Patchbay.type.bodyStrong)
            PText(detail, Patchbay.type.caption, c.textSecondary)
        }
    }
}

/** One glanceable line under the title: how many devices, what is on, the temperature, a print in progress. */
internal fun homeSummary(devices: List<LiveDevice>): String {
    val parts = mutableListOf(if (devices.size == 1) "1 device" else "${devices.size} devices")
    val online = devices.filter { it.snapshot?.reachability == Reachability.ONLINE }
    val on = online.count { (it.device.kind == DeviceKind.LIGHT || it.device.kind == DeviceKind.PLUG) && it.snapshot?.state<CapabilityState.PowerState>()?.on == true }
    if (on > 0) parts += "$on on"
    online.firstNotNullOfOrNull { it.snapshot?.state<CapabilityState.ThermostatState>() }?.let { parts += "${Fmt.celsius(it.current.value)}\u00A0°C" }
    online.firstNotNullOfOrNull { it.snapshot?.state<CapabilityState.PrintJobState>()?.takeIf { j -> j.stage == dev.patchbay.core.model.PrintStage.PRINTING } }
        ?.let { parts += "print ${Fmt.percent(it.progress.value)}\u00A0%" }
    return parts.joinToString(" · ")
}

/** The home: a large title, a Scan button, and one tile per device found on this network. */
@Composable
fun LiveDeviceList(live: LiveHome, selected: DeviceId?, onSelect: (DeviceId) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val devices by live.devices.collectAsState()
    val scan by live.scanState.collectAsState()
    val problem by live.problem.collectAsState()
    val report by live.scanReport.collectAsState()
    val rooms by live.rooms.collectAsState()
    val scope = rememberCoroutineScope()
    val pairing = remember { PairingDialogState() }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText("Home", Patchbay.type.largeTitle)
                PText(
                    when (scan) {
                        ScanState.IDLE -> if (devices.isEmpty()) "Scan to find your devices" else homeSummary(devices)
                        ScanState.SCANNING -> "Scanning your network…"
                        ScanState.DONE -> homeSummary(devices)
                        ScanState.FAILED -> "Scan failed"
                    },
                    Patchbay.type.body, c.textSecondary,
                )
                val silent = report.filter { it.discoverable && it.found == 0 }.map { it.name }
                if (scan == ScanState.DONE && silent.isNotEmpty()) {
                    PText("Nothing answered from ${silent.joinToString(", ")}. See Network for what to try.", Patchbay.type.caption, c.attention)
                }
            }
            PButton(
                if (scan == ScanState.SCANNING) "Scanning…" else "Scan", { scope.launch { live.scan() } },
                kind = ButtonKind.PRIMARY, enabled = scan != ScanState.SCANNING, busy = scan == ScanState.SCANNING,
            )
        }
        problem?.let {
            VSpace(Space.m)
            Row(Modifier.fillMaxWidth().background(c.attention.copy(alpha = 0.16f), Shapes.control).padding(start = Space.m), verticalAlignment = Alignment.CenterVertically) {
                ToneMarker(Tone.ATTENTION, Modifier.size(8.dp))
                HSpace(Space.s)
                PText(it, Patchbay.type.caption, c.attention, Modifier.weight(1f).padding(vertical = Space.m))
                Box(
                    Modifier.height(Space.touchTarget).clip(Shapes.control)
                        .clickable(role = Role.Button, onClickLabel = "Dismiss") { live.dismissProblem() }.padding(horizontal = Space.l),
                    contentAlignment = Alignment.Center,
                ) { PText("Dismiss", Patchbay.type.bodyStrong, c.attention) }
            }
        }
        VSpace(Space.l)
        PairingPanel(pairing)
        if (pairing.step != null) VSpace(Space.m)
        if (devices.isEmpty()) {
            FirstRunPanel(scan, scanning = scan == ScanState.SCANNING) { scope.launch { live.scan() } }
        } else {
            NeedsAttention(devices, onSelect)
            SceneStrip(live)
            val tileFor: @Composable (LiveDevice, Modifier) -> Unit = { d, m ->
                LiveTile(live, d, d.device.id == selected, onSelect, pairing, m)
            }
            val favs by live.favourites.collectAsState()
            val pinned = devices.filter { it.device.id in favs }
            if (pinned.isNotEmpty()) {
                PText("Favourites", Patchbay.type.title)
                VSpace(Space.s)
                TileGrid(pinned, tile = tileFor)
                VSpace(Space.l)
            }
            if (rooms.isEmpty()) {
                TileGrid(devices, tile = tileFor)
            } else {
                for (room in rooms) {
                    val inRoom = devices.filter { it.device.roomId?.value == room }
                    if (inRoom.isEmpty()) continue
                    PText(room, Patchbay.type.title)
                    VSpace(Space.s)
                    TileGrid(inRoom, tile = tileFor)
                    VSpace(Space.l)
                }
                val rest = devices.filter { it.device.roomId == null }
                if (rest.isNotEmpty()) {
                    PText("Other devices", Patchbay.type.title)
                    VSpace(Space.s)
                    TileGrid(rest, tile = tileFor)
                }
            }
        }
        ManualAddPanel(live)
    }
}

/**
 * Scenes as one row of pills under the header: one tap runs a scene, so the home screen leads with devices instead of setup panels.
 * Making, deleting and scheduling scenes lives on the Automations screen.
 */
@Composable
internal fun SceneStrip(live: LiveHome) {
    val c = Patchbay.colors
    val scenes by live.scenes.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    if (scenes.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        scenes.forEach { sc ->
            PButton(sc.name, {
                scope.launch {
                    message = when (val r = live.runScene(sc.id)) {
                        is dev.patchbay.core.result.PatchbayResult.Ok -> "${sc.name}: done"
                        is dev.patchbay.core.result.PatchbayResult.Err -> r.error.message
                    }
                }
            })
        }
        PButton("All off", {
            scope.launch {
                message = when (val r = live.allOff()) {
                    is dev.patchbay.core.result.PatchbayResult.Ok -> if (r.value == 0) "Everything is already off" else "Switched off ${r.value}"
                    is dev.patchbay.core.result.PatchbayResult.Err -> r.error.message
                }
            }
        }, kind = ButtonKind.SECONDARY)
    }
    message?.let {
        VSpace(Space.xs)
        PText(it, Patchbay.type.caption, c.textSecondary)
    }
    VSpace(Space.l)
}

/**
 * A device tile with optimistic switching: a tap flips a light or plug on screen at once (with a light haptic tick) and the
 * device's own report confirms it a moment later. If the command fails, or nothing confirms within three seconds, it flips back.
 */
@Composable
internal fun LiveTile(live: LiveHome, d: LiveDevice, selected: Boolean, onSelect: (DeviceId) -> Unit, pairing: PairingDialogState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val toggles = tileToggles(d)
    val actualOn = d.snapshot?.state<CapabilityState.PowerState>()?.on == true
    var pending by remember(d.device.id) { mutableStateOf<Boolean?>(null) }
    androidx.compose.runtime.LaunchedEffect(actualOn) { pending = null }
    androidx.compose.runtime.LaunchedEffect(pending) { if (pending != null) { kotlinx.coroutines.delay(3_000); pending = null } }
    DeviceTile(
        d, selected,
        onPrimary = {
            when {
                d.needsPairing -> scope.launch {
                    val r = live.pair(d.device.id, pairing)
                    pairing.done((r as? dev.patchbay.core.result.PatchbayResult.Err)?.error?.takeIf { it !is dev.patchbay.core.result.PatchbayError.Cancelled }?.message)
                }
                toggles -> {
                    val target = !(pending ?: actualOn)
                    pending = target
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    scope.launch {
                        if (live.send(d.device.id, dev.patchbay.core.model.Command.SetPower(target)) is dev.patchbay.core.result.PatchbayResult.Err) pending = null
                    }
                }
                else -> onSelect(d.device.id)
            }
        },
        onDetails = if (toggles) ({ onSelect(d.device.id) }) else null,
        modifier = modifier,
        optimisticOn = if (toggles) pending else null,
    )
}

/** Anything that needs a look, one tap from the main screen: a changed security key first, then devices that stopped answering. */
@Composable
internal fun NeedsAttention(devices: List<LiveDevice>, onSelect: (DeviceId) -> Unit) {
    val c = Patchbay.colors
    val trouble = devices.filter { it.trustChanged } + devices.filter { !it.trustChanged && it.snapshot?.reachability == Reachability.OFFLINE && !it.needsPairing }
    if (trouble.isEmpty()) return
    Panel(Modifier.fillMaxWidth()) {
        Legend("Needs attention")
        trouble.forEach { d ->
            VSpace(Space.s)
            Row(
                Modifier.fillMaxWidth().clip(Shapes.control).clickable(role = Role.Button) { onSelect(d.device.id) }.padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToneMarker(if (d.trustChanged) Tone.ALARM else Tone.ATTENTION, Modifier.size(10.dp))
                HSpace(Space.m)
                Column(Modifier.weight(1f)) {
                    PText(d.device.name, Patchbay.type.bodyStrong)
                    PText(
                        if (d.trustChanged) "Different security key: Patchbay stopped talking to it" else "No reply for a while",
                        Patchbay.type.caption, if (d.trustChanged) c.alarm else c.textSecondary,
                    )
                }
                PText("›", Patchbay.type.title, c.textSecondary)
            }
        }
    }
    VSpace(Space.l)
}

/** Phone: list, then detail. */
@Composable
fun LiveScreenPhone(live: LiveHome, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<DeviceId?>(null) }
    val c = Patchbay.colors
    val all by live.devices.collectAsState()
    // A removed device has no page to show: go back to the list.
    androidx.compose.runtime.LaunchedEffect(all, selected) { if (selected != null && all.none { it.device.id == selected }) selected = null }
    Column(modifier.fillMaxSize().background(c.background)) {
        val sel = selected
        val m = Patchbay.motion
        androidx.compose.animation.AnimatedContent(
            targetState = sel,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState != null
                val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(m.medium + 60, easing = m.easing)
                val fade = androidx.compose.animation.core.tween<Float>(m.medium, easing = m.easing)
                if (forward) {
                    (androidx.compose.animation.slideInHorizontally(slide) { it / 5 } + androidx.compose.animation.fadeIn(fade)) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(slide) { -it / 8 } + androidx.compose.animation.fadeOut(fade))
                } else {
                    (androidx.compose.animation.slideInHorizontally(slide) { -it / 8 } + androidx.compose.animation.fadeIn(fade)) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(slide) { it / 5 } + androidx.compose.animation.fadeOut(fade))
                }
            },
            label = "device-page",
        ) { shown ->
          if (shown == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l)) {
                LiveDeviceList(live, null, { selected = it })
            }
          } else {
            val device = shown
            Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(start = Space.s, top = Space.s).height(Space.touchTarget).clip(Shapes.pill)
                    .clickable(role = Role.Button) { selected = null }.padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PText("‹", Patchbay.type.title, c.textSecondary)
                HSpace(Space.s)
                PText("Home", Patchbay.type.bodyStrong, c.textSecondary)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l)) {
                LiveDetail(live, device)
            }
            }
        }
        }
    }
}

/** Desktop: list and detail side by side. */
@Composable
fun LiveScreenDesktop(live: LiveHome, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<DeviceId?>(null) }
    Row(modifier.fillMaxSize().padding(Space.xl)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            LiveDeviceList(live, selected, { selected = it })
        }
        HSpace(Space.xl)
        Box(Modifier.width(408.dp).fillMaxHeight()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                selected?.let { LiveDetail(live, it) } ?: PText("Select a device", Patchbay.type.body, Patchbay.colors.textSecondary)
            }
        }
    }
}

/**
 * Add a device by address, for networks where discovery can't see it (VLANs, client isolation).
 * Only shown when an adapter offers it. The device then pairs like a scanned one (Pair button).
 */
@Composable
internal fun ManualAddPanel(live: LiveHome) {
    val options = live.manualAdd
    if (options.isEmpty()) return
    val c = Patchbay.colors
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(0) }
    var profile by remember { mutableStateOf(0) }
    val option = options[chosen.coerceIn(0, options.lastIndex)]
    VSpace(Space.m)
    Panel(Modifier.fillMaxWidth(), padding = Space.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Add by address")
                if (!open) PText("For a device a scan can't see, such as an RTSP camera.", Patchbay.type.caption, c.textSecondary)
            }
            PButton(if (open) "Close" else "Add…", { open = !open })
        }
        if (open) {
            if (options.size > 1) {
                VSpace(Space.s)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    options.forEachIndexed { i, o ->
                        PButton(o.title, { chosen = i; error = null }, Modifier.weight(1f), if (i == chosen) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                    }
                }
            }
            if (option.choices.isNotEmpty()) {
                VSpace(Space.s)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    option.choices.forEachIndexed { i, label ->
                        PButton(label, { profile = i }, Modifier.weight(1f), if (i == profile) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                    }
                }
            }
            VSpace(Space.s)
            PText("IP address on your network", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            BasicTextField(
                value = address,
                onValueChange = { v -> address = v.filter { it.isLetterOrDigit() || it == '.' || it == ':' }.take(45) },
                singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.s),
            )
            VSpace(Space.s)
            PText(option.keyLabel, Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            BasicTextField(
                value = key,
                onValueChange = { v -> key = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(32) },
                singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.s),
            )
            error?.let {
                VSpace(Space.xs)
                PText(it, Patchbay.type.caption, c.alarm)
            }
            VSpace(Space.s)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton(
                    "Add", {
                        scope.launch {
                            when (val r = live.addManually(option.adapter, address, key, option.choices.getOrNull(profile))) {
                                is dev.patchbay.core.result.PatchbayResult.Ok -> { error = null; open = false; address = ""; key = "" }
                                is dev.patchbay.core.result.PatchbayResult.Err -> error = r.error.message
                            }
                        }
                    },
                    Modifier.weight(1f), ButtonKind.PRIMARY, enabled = address.isNotBlank() && (key.isNotBlank() || option.keyOptional),
                )
                PButton("Cancel", { open = false; error = null }, Modifier.weight(1f))
            }
            VSpace(Space.xs)
            PText("Then tap Pair on the new entry, as for a scanned device. No address ever leaves your network.", Patchbay.type.caption, c.textTertiary)
        }
    }
}
