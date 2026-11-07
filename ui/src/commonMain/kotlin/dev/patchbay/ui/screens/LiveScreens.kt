package dev.patchbay.ui.screens

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
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.launch

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
                        ScanState.IDLE -> if (devices.isEmpty()) "Scan to find your devices" else "${devices.size} devices"
                        ScanState.SCANNING -> "Scanning your network…"
                        ScanState.DONE -> "${devices.size} devices"
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
                kind = ButtonKind.PRIMARY, enabled = scan != ScanState.SCANNING,
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
            Panel(Modifier.fillMaxWidth()) {
                PText(
                    if (scan == ScanState.DONE) "Nothing answered" else "No devices yet",
                    Patchbay.type.title,
                )
                VSpace(Space.xs)
                PText(
                    if (scan == ScanState.DONE) "Govee lights need “LAN Control” switched on in the Govee app. Cameras, Tuya bulbs and printers can also be added by address below."
                    else "Tap Scan to look for devices on your Wi-Fi.",
                    Patchbay.type.body, c.textSecondary,
                )
            }
        } else {
            NeedsAttention(devices, onSelect)
            ScenesPanel(live)
            AutomationsPanel(live)
            AwayPanel(live)
            val tileFor: @Composable (LiveDevice, Modifier) -> Unit = { d, m ->
                val toggles = tileToggles(d)
                DeviceTile(
                    d, d.device.id == selected,
                    onPrimary = {
                        when {
                            d.needsPairing -> scope.launch {
                                val r = live.pair(d.device.id, pairing)
                                pairing.done((r as? dev.patchbay.core.result.PatchbayResult.Err)?.error?.takeIf { it !is dev.patchbay.core.result.PatchbayError.Cancelled }?.message)
                            }
                            toggles -> scope.launch {
                                val on = d.snapshot?.state<CapabilityState.PowerState>()?.on == true
                                live.send(d.device.id, dev.patchbay.core.model.Command.SetPower(!on))
                            }
                            else -> onSelect(d.device.id)
                        }
                    },
                    onDetails = if (toggles) ({ onSelect(d.device.id) }) else null,
                    modifier = m,
                )
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

/** What Patchbay can see and what it keeps to itself: the NetGuard counters, everything else that answered, the exposure report. */
@Composable
fun NetworkScreen(live: LiveHome, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val stats by live.netStats.collectAsState()
    Column(modifier) {
        PText("Network", Patchbay.type.largeTitle)
        PText("Patchbay talks only to devices on your home network.", Patchbay.type.body, c.textSecondary)
        VSpace(Space.l)
        Panel(Modifier.fillMaxWidth()) {
            Legend("Privacy")
            VSpace(Space.s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToneMarker(if (stats.denied > 0) Tone.ATTENTION else Tone.NEUTRAL, Modifier.size(8.dp))
                HSpace(Space.s)
                PText("LAN only", Patchbay.type.bodyStrong)
            }
            VSpace(Space.xs)
            PText(
                "${stats.allowed} local connections · ${stats.denied} refused" + if (stats.droppedInbound > 0) " · ${stats.droppedInbound} dropped" else "",
                Patchbay.type.caption, c.textSecondary,
            )
        }
        ScanReportPanel(live)
        CensusPanel(live)
        ExposurePanel(live)
        LogPanel(live)
        VSpace(Space.m)
        PText(
            "Supported now: Govee lights (LAN), TP-Link Kasa plugs, Tuya bulbs (by address), eufy RTSP cameras, LG webOS TVs, Bambu Lab printers (monitoring + pause/resume/stop). Cast, ESPHome and ecobee come next.",
            Patchbay.type.caption, c.textTertiary,
        )
    }
}

/**
 * Everything else that answered mDNS/SSDP. These are devices Patchbay doesn't manage (yet), with what their
 * advertised services say about local control. This is how you find the devices you'd forgotten.
 */
@Composable
private fun CensusPanel(live: LiveHome) {
    val c = Patchbay.colors
    val census by live.census.collectAsState()
    val scan by live.scanState.collectAsState()
    if (scan == ScanState.IDLE) return
    VSpace(Space.m)
    Panel(Modifier.fillMaxWidth(), padding = Space.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend("Everything else that answered")
            Box(Modifier.weight(1f))
            PText("${census.size}", Patchbay.type.valueS, c.textSecondary)
        }
        VSpace(Space.s)
        if (census.isEmpty()) {
            PText(
                if (scan == ScanState.SCANNING) "Listening for mDNS and SSDP replies…" else "No mDNS or SSDP replies. Guest-network isolation blocks these.",
                Patchbay.type.body, c.textSecondary,
            )
        }
        census.forEachIndexed { i, f ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
                DeviceGlyph(DeviceKind.UNKNOWN, dev.patchbay.ui.designsystem.GlyphState.UNMANAGED, Modifier.size(20.dp))
                HSpace(Space.m)
                Column(Modifier.weight(1f)) {
                    PText(f.friendlyName ?: f.model ?: f.hostNames.firstOrNull() ?: "Unnamed device", Patchbay.type.bodyStrong, maxLines = 1)
                    PText(listOfNotNull(f.address, f.model?.takeIf { f.friendlyName != null }, f.server).joinToString(" · "), Patchbay.type.mono, c.textSecondary, maxLines = 1)
                    val hints = f.hints
                    if (hints.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToneMarker(Tone.ATTENTION, Modifier.size(8.dp))
                            HSpace(Space.xs)
                            PText("Unidentified: services ${f.services.take(3).joinToString(", ").ifEmpty { "none advertised" }}", Patchbay.type.caption, c.attention, maxLines = 2)
                        }
                    } else {
                        PText(hints.joinToString(" · "), Patchbay.type.caption, c.textTertiary, maxLines = 2)
                    }
                }
            }
        }
    }
}

/**
 * Exposure summary plus an explicit "Copy report" action (the only way the report leaves the app). The
 * report includes suggested router rules as text; Patchbay never applies them.
 */
@Composable
private fun ExposurePanel(live: LiveHome) {
    val c = Patchbay.colors
    val devices by live.devices.collectAsState()
    val census by live.census.collectAsState()
    val scan by live.scanState.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (scan == ScanState.IDLE) return
    val report = remember(devices, census) { ExposureReport.build(devices, census, generatedAt = "this session") }
    VSpace(Space.m)
    Panel(Modifier.fillMaxWidth(), padding = Space.m) {
        Legend("Exposure")
        VSpace(Space.s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneMarker(Tone.NEUTRAL, Modifier.size(8.dp)); HSpace(Space.xs)
            PText("${report.local.size} local", Patchbay.type.valueS, c.text); HSpace(Space.l)
            ToneMarker(Tone.ATTENTION, Modifier.size(8.dp)); HSpace(Space.xs)
            PText("${report.cloudOnly.size} cloud-only", Patchbay.type.valueS, c.text); HSpace(Space.l)
            ToneMarker(Tone.ATTENTION, Modifier.size(8.dp)); HSpace(Space.xs)
            PText("${report.unknown.size} unidentified", Patchbay.type.valueS, c.text)
        }
        VSpace(Space.s)
        PText(
            "The report lists every device and how it can be controlled, plus suggested router rules to block internet " +
                "for devices Patchbay controls locally. Patchbay never changes your router.",
            Patchbay.type.caption, c.textTertiary,
        )
        VSpace(Space.s)
        PButton(if (copied) "Copied" else "Copy report", {
            clipboard.setText(AnnotatedString(report.text()))
            copied = true
        })
    }
}

/** A device's page: its controls, then which room it is in. */
@Composable
fun LiveDetail(live: LiveHome, id: DeviceId, modifier: Modifier = Modifier) {
    Column(modifier) {
        LiveDetailBody(live, id, Modifier)
        VSpace(Space.l)
        AdaptivePanel(live, id)
        RoomPanel(live, id)
        VSpace(Space.l)
        RemovePanel(live, id)
    }
}

/** The controls for a live device: lights get the LightPanel; anything else a summary. */
@Composable
private fun LiveDetailBody(live: LiveHome, id: DeviceId, modifier: Modifier = Modifier) {
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
    } else if (d.device.kind == DeviceKind.PLUG || d.device.capability<dev.patchbay.core.model.Capability.Power>() != null) {
        PlugPanel(d, onCommand = send, modifier = modifier)
    } else {
        Column(modifier) { PText(d.device.name, Patchbay.type.title) }
    }
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
        if (sel == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l)) {
                LiveDeviceList(live, null, { selected = it })
            }
        } else {
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
                LiveDetail(live, sel)
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
private fun ManualAddPanel(live: LiveHome) {
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

/** What each system found in the last scan, and what to try when it found nothing. This is how a missing device gets explained. */
@Composable
private fun ScanReportPanel(live: LiveHome) {
    val c = Patchbay.colors
    val report by live.scanReport.collectAsState()
    if (report.isEmpty()) return
    VSpace(Space.m)
    Panel(Modifier.fillMaxWidth()) {
        Legend("Last scan")
        report.forEachIndexed { i, r ->
            VSpace(Space.s)
            if (i > 0) Hairline()
            if (i > 0) VSpace(Space.s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PText(r.name, Patchbay.type.bodyStrong, modifier = Modifier.weight(1f))
                when {
                    !r.discoverable -> StatusTag("By address", Tone.NEUTRAL)
                    r.found == 0 -> StatusTag("None found", Tone.ATTENTION)
                    else -> StatusTag("${r.found} found", Tone.ACTIVE)
                }
            }
            val note = r.note
            val explanation = when {
                !r.discoverable -> "These don't announce themselves. Add them with Add by address on the Devices tab."
                r.found == 0 -> note
                else -> null
            }
            if (explanation != null) {
                VSpace(Space.xs)
                PText(explanation, Patchbay.type.caption, c.textSecondary)
            }
        }
    }
}

/** The redacted session log, copied only when asked. Passwords, codes and keys are already removed. */
@Composable
private fun LogPanel(live: LiveHome) {
    val c = Patchbay.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    VSpace(Space.m)
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Session log")
                PText("Copies the latest log lines with secrets removed. Nothing is sent anywhere; you paste it where you choose.", Patchbay.type.caption, c.textSecondary)
            }
            HSpace(Space.m)
            PButton(if (copied) "Copied" else "Copy log", { clipboard.setText(AnnotatedString(live.recentLog())); copied = true })
        }
    }
}

/** Saved scenes with a Run button each, and a way to make a new one from the lights and plugs as they are right now. */
@Composable
private fun ScenesPanel(live: LiveHome) {
    val c = Patchbay.colors
    val scenes by live.scenes.collectAsState()
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val eligible = devices.filter {
        (it.device.kind == DeviceKind.LIGHT || it.device.kind == DeviceKind.PLUG) &&
            it.snapshot?.reachability == Reachability.ONLINE && it.snapshot?.state<CapabilityState.PowerState>() != null
    }
    if (scenes.isEmpty() && eligible.isEmpty()) return
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<DeviceId>()) }
    var message by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Scenes")
                if (scenes.isEmpty() && !creating) PText("One tap sets several lights and plugs the way they are now.", Patchbay.type.caption, c.textSecondary)
            }
            if (!creating && eligible.isNotEmpty()) {
                PButton("All off", {
                    scope.launch {
                        message = when (val r = live.allOff()) {
                            is dev.patchbay.core.result.PatchbayResult.Ok -> if (r.value == 0) "Everything is already off" else "Switched off ${r.value}"
                            is dev.patchbay.core.result.PatchbayResult.Err -> r.error.message
                        }
                    }
                })
                HSpace(Space.s)
                PButton("New scene", { creating = true; message = null; picked = eligible.map { it.device.id }.toSet() })
            }
        }
        scenes.forEach { sc ->
            VSpace(Space.s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PText(sc.name, Patchbay.type.bodyStrong)
                    PText("${sc.devices.size} devices", Patchbay.type.caption, c.textSecondary)
                }
                Box(
                    Modifier.height(Space.touchTarget).clip(Shapes.pill)
                        .clickable(role = Role.Button, onClickLabel = "Delete ${sc.name}") { scope.launch { live.deleteScene(sc.id) } }
                        .padding(horizontal = Space.m),
                    contentAlignment = Alignment.Center,
                ) { PText("Delete", Patchbay.type.caption, c.textSecondary) }
                HSpace(Space.xs)
                PButton("Run", {
                    scope.launch {
                        message = when (val r = live.runScene(sc.id)) {
                            is dev.patchbay.core.result.PatchbayResult.Ok -> "${sc.name}: done"
                            is dev.patchbay.core.result.PatchbayResult.Err -> r.error.message
                        }
                    }
                }, kind = ButtonKind.PRIMARY)
            }
        }
        message?.let {
            VSpace(Space.s)
            PText(it, Patchbay.type.caption, c.textSecondary)
        }
        if (creating) {
            VSpace(Space.m)
            PText("Name", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            BasicTextField(
                value = name,
                onValueChange = { v -> name = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(32) },
                singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.m),
            )
            VSpace(Space.s)
            PText("Include", Patchbay.type.bodyStrong)
            eligible.forEach { d ->
                PToggle(d.device.name, d.device.id in picked, { on -> picked = if (on) picked + d.device.id else picked - d.device.id })
            }
            VSpace(Space.s)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton(
                    "Save scene", {
                        scope.launch {
                            when (val r = live.saveScene(name, picked.toList())) {
                                is dev.patchbay.core.result.PatchbayResult.Ok -> { creating = false; name = ""; message = null }
                                is dev.patchbay.core.result.PatchbayResult.Err -> message = r.error.message
                            }
                        }
                    },
                    Modifier.weight(1f), ButtonKind.PRIMARY, enabled = name.isNotBlank() && picked.isNotEmpty(),
                )
                PButton("Cancel", { creating = false; message = null }, Modifier.weight(1f))
            }
            VSpace(Space.xs)
            PText("Saves each device's power, brightness and colour as they are right now.", Patchbay.type.caption, c.textTertiary)
        }
    }
    VSpace(Space.l)
}

/** Which room this device is in: pick an existing room, none, or type a new one. */
@Composable
private fun RoomPanel(live: LiveHome, id: DeviceId) {
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

/** Anything that needs a look, one tap from the main screen: a changed security key first, then devices that stopped answering. */
@Composable
private fun NeedsAttention(devices: List<LiveDevice>, onSelect: (DeviceId) -> Unit) {
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

/** The loud one (ADR 0005): the device is not who you paired. No controls are shown. */
@Composable
private fun TrustAlert(d: LiveDevice, modifier: Modifier) {
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
private fun OfflineNotice(d: LiveDevice, modifier: Modifier) {
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
private fun RemovePanel(live: LiveHome, id: DeviceId) {
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

/** What changed and when, newest first, kept on this device. Copying it as CSV is the only way it leaves. */
@Composable
fun ActivityScreen(live: LiveHome, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val entries by live.activity.collectAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText("Activity", Patchbay.type.largeTitle)
                PText("What changed and when. Stays on this device.", Patchbay.type.body, c.textSecondary)
            }
            if (entries.isNotEmpty()) PButton(if (copied) "Copied" else "Copy as CSV", { clipboard.setText(AnnotatedString(live.activityCsv())); copied = true })
        }
        VSpace(Space.l)
        if (entries.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                PText("Nothing yet", Patchbay.type.title)
                VSpace(Space.xs)
                PText("Switching a light, a device going offline, a scene running: each shows up here as it happens.", Patchbay.type.body, c.textSecondary)
            }
        } else {
            Panel(Modifier.fillMaxWidth()) {
                entries.take(150).forEachIndexed { i, e ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            PText(e.deviceName, Patchbay.type.bodyStrong)
                            PText(e.event, Patchbay.type.caption, c.textSecondary)
                        }
                        HSpace(Space.m)
                        PText(live.timeLabel(e.atMillis), Patchbay.type.caption, c.textTertiary)
                    }
                }
            }
        }
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

private fun daysText(days: Set<Int>): String = when {
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
}

/** Run a scene at a time on chosen days. Only while Patchbay is open or in the tray; the panel says so. */
@Composable
private fun AutomationsPanel(live: LiveHome) {
    val c = Patchbay.colors
    val automations by live.automations.collectAsState()
    val scenes by live.scenes.collectAsState()
    val scope = rememberCoroutineScope()
    if (scenes.isEmpty() && automations.isEmpty()) return
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("07:30") }
    var sceneId by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5, 6, 7)) }
    var error by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Automations")
                PText("Run a scene at a set time. Only while Patchbay is open or in the tray.", Patchbay.type.caption, c.textSecondary)
            }
            if (!creating && scenes.isNotEmpty()) PButton("New", { creating = true; error = null; sceneId = scenes.first().id })
        }
        automations.forEach { a ->
            VSpace(Space.s)
            PToggle(a.name, a.enabled, { on -> scope.launch { live.setAutomationEnabled(a.id, on) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                PText(
                    "%02d:%02d · %s · %s".format(a.hour, a.minute, daysText(a.days), a.sceneName ?: "scene deleted"),
                    Patchbay.type.caption, if (a.sceneName == null) c.attention else c.textSecondary, Modifier.weight(1f),
                )
                Box(
                    Modifier.height(Space.touchTarget).clip(Shapes.pill)
                        .clickable(role = Role.Button, onClickLabel = "Delete ${a.name}") { scope.launch { live.deleteAutomation(a.id) } }
                        .padding(horizontal = Space.m),
                    contentAlignment = Alignment.Center,
                ) { PText("Delete", Patchbay.type.caption, c.textSecondary) }
            }
        }
        if (creating) {
            VSpace(Space.m)
            PText("Name", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            BasicTextField(
                value = name, onValueChange = { v -> name = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(32) }, singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.m),
            )
            VSpace(Space.s)
            PText("Scene", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            scenes.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { sc -> PButton(sc.name, { sceneId = sc.id }, Modifier.weight(1f), if (sc.id == sceneId) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
                VSpace(Space.xs)
            }
            VSpace(Space.xs)
            PText("Time (24 hour, HH:MM)", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            BasicTextField(
                value = time, onValueChange = { v -> time = v.filter { it.isDigit() || it == ':' }.take(5) }, singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.m),
            )
            VSpace(Space.s)
            PText("Days", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                DAY_LETTERS.forEachIndexed { i, letter ->
                    val d = i + 1
                    PButton(letter, { days = if (d in days) days - d else days + d }, Modifier.weight(1f), if (d in days) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
            }
            error?.let {
                VSpace(Space.xs)
                PText(it, Patchbay.type.caption, c.alarm)
            }
            VSpace(Space.s)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton(
                    "Save", {
                        val parts = time.split(':')
                        val h = parts.getOrNull(0)?.toIntOrNull()
                        val m = parts.getOrNull(1)?.toIntOrNull()
                        val sid = sceneId
                        if (h == null || m == null || parts.size != 2) error = "Enter the time as HH:MM, for example 07:30"
                        else if (sid == null) error = "Pick a scene"
                        else scope.launch {
                            when (val r = live.saveAutomation(name, sid, h, m, days)) {
                                is dev.patchbay.core.result.PatchbayResult.Ok -> { creating = false; name = ""; error = null }
                                is dev.patchbay.core.result.PatchbayResult.Err -> error = r.error.message
                            }
                        }
                    },
                    Modifier.weight(1f), ButtonKind.PRIMARY, enabled = name.isNotBlank(),
                )
                PButton("Cancel", { creating = false; error = null }, Modifier.weight(1f))
            }
        }
    }
    VSpace(Space.l)
}

/** Away mode: everything off now, and optionally the lights imitate someone being home in the evening. */
@Composable
private fun AwayPanel(live: LiveHome) {
    val c = Patchbay.colors
    val away by live.away.collectAsState()
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val lights = devices.filter { it.device.kind == DeviceKind.LIGHT }.map { it.device.id }
    if (lights.isEmpty() && !away.active) return
    var simulate by remember { mutableStateOf(true) }
    Panel(Modifier.fillMaxWidth()) {
        Legend("Away mode")
        VSpace(Space.xs)
        PToggle("Away", away.active, { on -> scope.launch { live.setAway(on, if (on && simulate) lights.toSet() else emptySet()) } })
        if (!away.active) {
            PToggle("Imitate someone home with my lights", simulate, { simulate = it })
        }
        VSpace(Space.xs)
        PText(
            if (away.active) "Away is on. Everything was switched off${if (away.simulate.isNotEmpty()) "; lights switch on and off between 18:00 and 23:00 as if someone were home" else ""}."
            else "Turning it on switches everything off now. With the imitation on, lights flip at irregular times in the evening and go off at 23:00. It runs only while Patchbay is open or in the tray.",
            Patchbay.type.caption, c.textSecondary,
        )
    }
    VSpace(Space.l)
}

/** Adaptive lighting for a light with a white colour temperature: cool at midday, warm in the evening, never overriding a change you make. */
@Composable
private fun AdaptivePanel(live: LiveHome, id: DeviceId) {
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
