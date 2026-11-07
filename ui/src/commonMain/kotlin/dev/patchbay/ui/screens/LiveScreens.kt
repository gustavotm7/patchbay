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
            TileGrid(devices) { d, m ->
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

/** Detail for a live device: lights get the LightPanel; anything else a summary. */
@Composable
fun LiveDetail(live: LiveHome, id: DeviceId, modifier: Modifier = Modifier) {
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val d = devices.firstOrNull { it.device.id == id } ?: return
    val send: (dev.patchbay.core.model.Command) -> Unit = { cmd -> scope.launch { live.send(id, cmd) } }
    if (d.device.kind == DeviceKind.LIGHT) {
        LightPanel(d, onCommand = send, modifier = modifier)
    } else if (d.device.kind == DeviceKind.TV) {
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
