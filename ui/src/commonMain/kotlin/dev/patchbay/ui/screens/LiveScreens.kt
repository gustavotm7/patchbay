package dev.patchbay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.launch

/** Header + scan control + NetGuard line + the list of devices found on this network. */
@Composable
fun LiveDeviceList(live: LiveHome, selected: DeviceId?, onSelect: (DeviceId) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val devices by live.devices.collectAsState()
    val scan by live.scanState.collectAsState()
    val stats by live.netStats.collectAsState()
    val problem by live.problem.collectAsState()
    val scope = rememberCoroutineScope()

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText("On this network", Patchbay.type.title)
                PText(
                    when (scan) {
                        ScanState.IDLE -> "Not scanned yet"
                        ScanState.SCANNING -> "Scanning…"
                        ScanState.DONE -> "${devices.size} found"
                        ScanState.FAILED -> "Scan failed"
                    },
                    Patchbay.type.caption, c.textSecondary,
                )
            }
            PButton(
                if (scan == ScanState.SCANNING) "Scanning…" else "Scan", { scope.launch { live.scan() } },
                kind = ButtonKind.PRIMARY, enabled = scan != ScanState.SCANNING,
            )
        }
        VSpace(Space.s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneMarker(if (stats.denied > 0) Tone.ATTENTION else Tone.NEUTRAL, Modifier.size(8.dp))
            HSpace(Space.s)
            PText(
                "NetGuard: LAN only · ${stats.allowed} local · ${stats.denied} refused" +
                    if (stats.droppedInbound > 0) " · ${stats.droppedInbound} dropped" else "",
                Patchbay.type.caption, c.textSecondary,
            )
        }
        problem?.let {
            VSpace(Space.s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToneMarker(Tone.ATTENTION, Modifier.size(8.dp))
                HSpace(Space.s)
                PText(it, Patchbay.type.caption, c.attention)
            }
        }
        VSpace(Space.l)
        Panel(Modifier.fillMaxWidth(), padding = Space.m) {
            Legend("Devices")
            VSpace(Space.s)
            if (devices.isEmpty()) {
                PText(
                    if (scan == ScanState.DONE) "Nothing answered. Govee lights need “LAN Control” switched on in the Govee app."
                    else "Tap Scan to look for devices on your Wi-Fi.",
                    Patchbay.type.body, c.textSecondary,
                )
            }
            devices.forEachIndexed { i, d ->
                if (i > 0) Hairline()
                LiveRow(d, d.device.id == selected) { onSelect(d.device.id) }
            }
        }
        CensusPanel(live)
        VSpace(Space.s)
        PText(
            "Supported now: Govee LAN. LG TV, Cast, Bambu, ESPHome and ecobee arrive in later Phase 1/2 builds.",
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

@Composable
private fun LiveRow(d: LiveDevice, selected: Boolean, onClick: () -> Unit) {
    val c = Patchbay.colors
    val s = d.snapshot
    Row(
        Modifier.fillMaxWidth().background(if (selected) c.raised else c.surface).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceGlyph(
            d.device.kind, DeviceViews.glyphState(d.device, s), Modifier.size(24.dp),
            emitted = DeviceViews.emittedColor(d.device, s, c),
        )
        HSpace(Space.m)
        Column(Modifier.weight(1f)) {
            PText(d.device.name, Patchbay.type.bodyStrong)
            PText(DeviceViews.controlPathLabel(d.device.controlPath), Patchbay.type.caption, c.textSecondary)
        }
        when (s?.reachability) {
            Reachability.OFFLINE -> StatusTag("Offline", Tone.ALARM)
            Reachability.DEGRADED -> StatusTag("No reply", Tone.ATTENTION)
            null -> PText("…", Patchbay.type.valueS, c.textTertiary)
            else -> {
                val p = s.state<CapabilityState.PowerState>()
                val b = s.state<CapabilityState.BrightnessState>()
                PText(if (p?.on == true) (b?.let { "${Fmt.percent(it.level.value)} %" } ?: "On") else "Off", Patchbay.type.valueS, c.text)
            }
        }
    }
}

/** Detail for a live device: lights get the LightPanel; anything else a summary. */
@Composable
fun LiveDetail(live: LiveHome, id: DeviceId, modifier: Modifier = Modifier) {
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val d = devices.firstOrNull { it.device.id == id } ?: return
    if (d.device.kind == DeviceKind.LIGHT) {
        LightPanel(d, onCommand = { cmd -> scope.launch { live.send(id, cmd) } }, modifier = modifier)
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
                Modifier.fillMaxWidth().clickable(role = Role.Button) { selected = null }.padding(horizontal = Space.l, vertical = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PText("‹", Patchbay.type.title, c.textSecondary)
                HSpace(Space.s)
                PText("On this network", Patchbay.type.bodyStrong, c.textSecondary)
            }
            Hairline()
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
