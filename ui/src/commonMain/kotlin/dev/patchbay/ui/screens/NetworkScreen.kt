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

// Network (what Patchbay sees and keeps to itself) and Activity (what changed, and when).

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
internal fun CensusPanel(live: LiveHome) {
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
internal fun ExposurePanel(live: LiveHome) {
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

/** What each system found in the last scan, and what to try when it found nothing. This is how a missing device gets explained. */
@Composable
internal fun ScanReportPanel(live: LiveHome) {
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
internal fun LogPanel(live: LiveHome) {
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
