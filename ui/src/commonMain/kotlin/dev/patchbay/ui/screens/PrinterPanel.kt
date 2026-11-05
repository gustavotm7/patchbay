package dev.patchbay.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.FilamentSlot
import dev.patchbay.core.model.HeaterReading
import dev.patchbay.core.model.Metric
import dev.patchbay.core.model.PrintAction
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Severity
import dev.patchbay.core.model.TrendSource
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.Fill
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.HSpace
import dev.patchbay.ui.designsystem.Hairline
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
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import dev.patchbay.ui.designsystem.ValueRow
import dev.patchbay.ui.designsystem.hatch

/**
 * Printer monitoring panel. Scope (brief §4): monitoring + pause/resume/stop + light only.
 * Stop needs a second, explicit confirmation. There are no heating, motion or G-code controls.
 */
@Composable
fun PrinterPanel(
    device: Device,
    snapshot: DeviceSnapshot,
    trends: TrendSource,
    roomName: String?,
    modifier: Modifier = Modifier,
    onPrintAction: (PrintAction) -> Unit = {},
    onLight: (Boolean) -> Unit = {},
) {
    val c = Patchbay.colors
    val job = snapshot.state<CapabilityState.PrintJobState>() ?: return
    val thermals = snapshot.state<CapabilityState.PrinterThermalsState>()
    val slots = snapshot.state<CapabilityState.FilamentSlotsState>()
    val light = snapshot.state<CapabilityState.AuxLightState>()
    val phone = Patchbay.formFactor == FormFactor.PHONE
    var confirmStop by remember { mutableStateOf(false) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(device.name, Patchbay.type.title)
                PText(listOfNotNull(roomName, device.vendor, device.firmware?.let { "fw $it" }).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            val (label, tone) = when (job.stage) {
                PrintStage.PRINTING -> "Printing" to Tone.ACTIVE
                PrintStage.PREPARING -> "Preparing" to Tone.ACTIVE
                PrintStage.PAUSED -> "Paused" to Tone.ATTENTION
                PrintStage.FAILED -> "Failed" to Tone.ALARM
                PrintStage.FINISHED -> "Finished" to Tone.NEUTRAL
                PrintStage.IDLE -> "Idle" to Tone.NEUTRAL
            }
            StatusTag(label, tone)
        }
        VSpace(Space.l)

        // Job: layer stack + numbers
        Panel(Modifier.fillMaxWidth(), padding = Space.m) {
            Legend("Job")
            VSpace(Space.xs)
            PText(job.fileName ?: "—", Patchbay.type.mono, c.textSecondary, maxLines = 1)
            VSpace(Space.m)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LayerStack(job.layer, job.totalLayers, Modifier.width(if (phone) 96.dp else 112.dp).height(if (phone) 150.dp else 168.dp))
                HSpace(Space.xl)
                Column {
                    Readout(Fmt.percent(job.progress.value), "%", Patchbay.type.valueXL, spokenUnit = "percent", label = "Progress")
                    VSpace(Space.s)
                    Legend("Layer")
                    Readout("${job.layer}", "/ ${job.totalLayers}", Patchbay.type.valueM, spokenUnit = "of ${job.totalLayers}", label = "Layer")
                    VSpace(Space.s)
                    job.remainingMinutes?.let { rem ->
                        PText("${Fmt.duration(rem)} left", Patchbay.type.valueS, c.text)
                        PText("ETA ${Fmt.timeOfDay(snapshot.observedAt.value + rem * 60_000L)} UTC", Patchbay.type.caption, c.textTertiary)
                    }
                }
            }
        }
        VSpace(Space.m)

        // Thermals (read-only). Heaters with a target use the heat colour: they are actively heating.
        thermals?.let { th ->
            Panel(Modifier.fillMaxWidth(), padding = Space.m) {
                Legend("Temperatures")
                VSpace(Space.xs)
                val nozzleMetrics = listOf(Metric.NOZZLE_1, Metric.NOZZLE_2)
                th.nozzles.forEachIndexed { i, n ->
                    val name = if (th.nozzles.size == 2) (if (i == 0) "Left nozzle" else "Right nozzle") else "Nozzle"
                    HeaterRow(name, n, trends.trend(device.id, nozzleMetrics.getOrElse(i) { Metric.NOZZLE_1 }))
                }
                HeaterRow("Bed", th.bed, trends.trend(device.id, Metric.BED))
                th.chamber?.let { HeaterRow("Chamber", it, trends.trend(device.id, Metric.CHAMBER)) }
            }
            VSpace(Space.m)
        }

        // Filament (AMS)
        slots?.let { s ->
            Panel(Modifier.fillMaxWidth(), padding = Space.m) {
                Legend("AMS")
                VSpace(Space.s)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    s.slots.forEach { slot -> FilamentSwatch(slot, Modifier.weight(1f)) }
                }
            }
            VSpace(Space.m)
        }

        // HMS
        Panel(Modifier.fillMaxWidth(), padding = Space.m) {
            Legend("HMS")
            VSpace(Space.xs)
            if (job.hms.isEmpty()) {
                PText("No active health events", Patchbay.type.body, c.textSecondary)
            } else {
                job.hms.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Space.xxs)) {
                        ToneMarker(if (e.severity == Severity.FAULT) Tone.ALARM else Tone.ATTENTION, Modifier.size(8.dp))
                        HSpace(Space.s)
                        PText(e.code, Patchbay.type.mono, c.text)
                        HSpace(Space.s)
                        PText(e.summary, Patchbay.type.body, c.textSecondary, maxLines = 2)
                    }
                }
            }
        }
        VSpace(Space.m)

        // Controls: deliberately few. Stop is two-step.
        Legend("Control")
        VSpace(Space.s)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (job.stage == PrintStage.PAUSED) {
                PButton("Resume", { onPrintAction(PrintAction.RESUME) }, Modifier.weight(1f), ButtonKind.PRIMARY)
            } else {
                PButton("Pause", { onPrintAction(PrintAction.PAUSE) }, Modifier.weight(1f), ButtonKind.PRIMARY, enabled = job.stage == PrintStage.PRINTING)
            }
            if (!confirmStop) {
                PButton("Stop…", { confirmStop = true }, Modifier.weight(1f), ButtonKind.DESTRUCTIVE, enabled = job.stage == PrintStage.PRINTING || job.stage == PrintStage.PAUSED)
            } else {
                PButton("Confirm stop", { confirmStop = false; onPrintAction(PrintAction.STOP) }, Modifier.weight(1f), ButtonKind.DESTRUCTIVE)
            }
        }
        if (confirmStop) {
            VSpace(Space.xs)
            PText("Stopping cancels the job. It can't be resumed.", Patchbay.type.caption, c.alarm)
        }
        light?.let {
            VSpace(Space.s)
            PToggle(it.lightName, it.on, onLight)
        }
        VSpace(Space.m)
        Hairline()
        VSpace(Space.s)
        PText(
            "Monitoring scope: pause, resume, stop and light only. Heating, motion and G-code aren't available from Patchbay.",
            Patchbay.type.caption, c.textTertiary,
        )
    }
}

@Composable
private fun HeaterRow(name: String, r: HeaterReading, trend: dev.patchbay.core.model.Trend?) {
    val c = Patchbay.colors
    val heating = r.target != null
    ValueRow(
        label = name,
        value = Fmt.celsius(r.current.value),
        unit = "°C",
        detail = r.target?.let { "target ${Fmt.fixed(it.value, 0)} °C" } ?: "idle",
        trend = trend,
        trendColor = if (heating) c.heat else c.textSecondary,
        valueColor = c.text,
        spokenUnit = "degrees Celsius",
    )
}

/**
 * Progress as a physical stack of layers: completed slices are filled, the current slice is
 * outlined in the primary text colour, remaining slices are hairline outlines.
 */
@Composable
fun LayerStack(layer: Int, total: Int, modifier: Modifier = Modifier, slices: Int = 24) {
    val c = Patchbay.colors
    Canvas(modifier.semantics { contentDescription = "Layer $layer of $total" }) {
        if (total <= 0) return@Canvas
        val gap = 2.dp.toPx()
        val h = (size.height - gap * (slices - 1)) / slices
        val done = (layer.toDouble() / total * slices).toInt().coerceIn(0, slices)
        for (i in 0 until slices) {
            // Bottom slice is layer 1. Slight taper reads as a 3D object without perspective tricks.
            val y = size.height - (i + 1) * h - i * gap
            val inset = (i.toFloat() / slices) * size.width * 0.12f
            val o = Offset(inset, y)
            val s = Size(size.width - 2 * inset, h)
            when {
                i < done -> drawRect(c.active, o, s)
                i == done -> drawRect(c.text, o, s, style = Stroke(1.5.dp.toPx()))
                else -> drawRect(c.divider, o, s, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
fun FilamentSwatch(slot: FilamentSlot, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val low = slot.remaining?.let { it.value < 15.0 } ?: false
    Column(
        modifier
            .border(if (slot.active) 2.dp else Space.hair, if (slot.active) c.text else c.divider, Shapes.control)
            .padding(Space.s)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    "Slot ${slot.index}",
                    slot.material ?: "empty",
                    slot.remaining?.let { "${Fmt.percent(it.value)} percent" },
                    if (slot.active) "in use" else null,
                    if (low) "low" else null,
                ).joinToString(", ")
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(c.raised, Shapes.chip).border(Space.hair, c.outline, Shapes.chip)) {
            if (slot.colour != null) {
                // Real filament colour: this is the device's actual material, not decoration.
                Box(Modifier.fillMaxSize().padding(3.dp).background(Color(slot.colour!!.r, slot.colour!!.g, slot.colour!!.b), Shapes.chip))
            } else {
                Canvas(Modifier.fillMaxSize()) { hatch(c.divider, 6.dp.toPx(), 1.dp.toPx()) }
            }
        }
        VSpace(Space.xs)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PText("${slot.index}", Patchbay.type.valueS, c.textSecondary)
            HSpace(Space.xs)
            PText(slot.material ?: "Empty", Patchbay.type.bodyStrong, if (slot.material != null) c.text else c.textTertiary, maxLines = 1)
        }
        slot.remaining?.let { rem ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (low) {
                    ToneMarker(Tone.ATTENTION, Modifier.size(8.dp))
                    HSpace(Space.xs)
                }
                Readout(Fmt.percent(rem.value), "%", Patchbay.type.valueS, color = if (low) c.attention else c.text, spokenUnit = "percent")
            }
            VSpace(Space.xxs)
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.divider)) {
                Box(Modifier.fillMaxWidth(rem.fraction.toFloat()).height(3.dp).background(if (low) c.attention else c.textSecondary))
            }
        } ?: PText("—", Patchbay.type.valueS, c.textTertiary)
        if (slot.active) {
            VSpace(Space.xxs)
            PText("IN USE", Patchbay.type.legend, c.text)
        }
    }
}
