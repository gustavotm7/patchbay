package dev.patchbay.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Celsius
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.HvacAction
import dev.patchbay.core.model.HvacMode
import dev.patchbay.core.model.Metric
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
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Readout
import dev.patchbay.ui.designsystem.Segmented
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.Sparkline
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import dev.patchbay.ui.designsystem.ValueRow
import kotlin.math.ceil
import kotlin.math.floor

/** Setpoint edits are reported as (heat, cool) after snapping and deadband enforcement. */
fun interface SetpointListener {
    fun onSetpoints(heat: Celsius?, cool: Celsius?)
}

@Composable
fun ThermostatPanel(
    device: Device,
    snapshot: DeviceSnapshot,
    trends: TrendSource,
    roomName: String?,
    modifier: Modifier = Modifier,
    onMode: (HvacMode) -> Unit = {},
    onSetpoints: SetpointListener = SetpointListener { _, _ -> },
) {
    val cap = device.capability<Capability.Thermostat>() ?: return
    val initial = snapshot.state<CapabilityState.ThermostatState>() ?: return
    val c = Patchbay.colors
    val phone = Patchbay.formFactor == FormFactor.PHONE

    // Local, optimistic UI state; the adapter's next snapshot is the source of truth.
    var heat by remember(initial) { mutableStateOf(initial.heatSetpoint) }
    var cool by remember(initial) { mutableStateOf(initial.coolSetpoint) }
    var mode by remember(initial) { mutableStateOf(initial.mode) }
    val humidity = snapshot.state<CapabilityState.HumidityReading>()

    fun update(h: Celsius?, k: Celsius?) {
        heat = h
        cool = k
        onSetpoints.onSetpoints(h, k)
    }

    Column(modifier) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(device.name, Patchbay.type.title)
                PText(listOfNotNull(roomName, device.vendor).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            val (label, tone) = when (initial.action) {
                HvacAction.HEATING -> "Heating" to Tone.HEAT
                HvacAction.COOLING -> "Cooling" to Tone.COOL
                HvacAction.FAN_ONLY -> "Fan" to Tone.ACTIVE
                HvacAction.IDLE -> "Idle" to Tone.NEUTRAL
            }
            StatusTag(label, tone)
        }
        VSpace(Space.l)

        // Hero readout
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Legend("Indoor")
                Readout(Fmt.celsius(initial.current.value), "°C", Patchbay.type.valueXL, spokenUnit = "degrees Celsius", label = "Indoor temperature")
            }
            Fill()
            trends.trend(device.id, Metric.INDOOR_TEMPERATURE)?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Sparkline(it, Modifier.width(if (phone) 120.dp else 140.dp).height(36.dp))
                    PText("24 h", Patchbay.type.caption, c.textTertiary)
                }
            }
        }
        VSpace(Space.l)

        Panel(Modifier.fillMaxWidth(), padding = Space.m) {
            Row {
                SetpointScale(
                    current = initial.current, heat = heat, cool = cool, cap = cap,
                    heatActive = mode == HvacMode.HEAT || mode == HvacMode.AUTO,
                    coolActive = mode == HvacMode.COOL || mode == HvacMode.AUTO,
                    modifier = Modifier.width(if (phone) 148.dp else 168.dp).height(if (phone) 360.dp else 400.dp),
                    onChange = ::update,
                )
                HSpace(Space.l)
                Column(Modifier.weight(1f)) {
                    SetpointControl(
                        "Heat to", heat, Tone.HEAT, enabled = mode == HvacMode.HEAT || mode == HvacMode.AUTO,
                        onStep = { d -> heat?.let { h -> update(clampHeat(h.value + d, cool, cap, mode), cool) } },
                    )
                    VSpace(Space.l)
                    SetpointControl(
                        "Cool to", cool, Tone.COOL, enabled = mode == HvacMode.COOL || mode == HvacMode.AUTO,
                        onStep = { d -> cool?.let { k -> update(heat, clampCool(k.value + d, heat, cap, mode)) } },
                    )
                    VSpace(Space.l)
                    Hairline()
                    VSpace(Space.s)
                    val band = if (heat != null && cool != null) cool!! - heat!! else null
                    ValueRow("Deadband", band?.let { Fmt.fixed(it, 1) } ?: "—", "°C", detail = "minimum ${Fmt.fixed(cap.minimumDeadband, 1)} °C")
                    humidity?.let {
                        ValueRow(
                            "Humidity", Fmt.percent(it.relative.value), "%", trend = trends.trend(device.id, Metric.HUMIDITY),
                            spokenUnit = "percent",
                        )
                    }
                }
            }
        }
        VSpace(Space.m)

        Legend("Mode")
        VSpace(Space.s)
        Segmented(
            options = listOf(HvacMode.OFF, HvacMode.HEAT, HvacMode.COOL, HvacMode.AUTO).filter { it in cap.modes },
            selected = mode,
            label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            onSelect = { mode = it; onMode(it) },
            tone = { when (it) { HvacMode.HEAT -> Tone.HEAT; HvacMode.COOL -> Tone.COOL; else -> Tone.NEUTRAL } },
            modifier = Modifier.fillMaxWidth(),
        )
        VSpace(Space.l)

        val occupancy = snapshot.states.filterIsInstance<CapabilityState.OccupancyReading>()
        if (occupancy.isNotEmpty()) {
            Legend("Occupancy sensors")
            VSpace(Space.xs)
            occupancy.forEach { o ->
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    PText(o.sensorName, Patchbay.type.body, c.textSecondary, Modifier.weight(1f))
                    ToneMarker(if (o.occupied) Tone.ACTIVE else Tone.NEUTRAL, Modifier.size(8.dp))
                    HSpace(Space.s)
                    PText(if (o.occupied) "Occupied" else "Clear", Patchbay.type.bodyStrong, if (o.occupied) c.text else c.textSecondary)
                }
            }
            VSpace(Space.m)
        }
        PText(
            "Local · HomeKit Accessory Protocol · updated ${Fmt.timeOfDay(snapshot.observedAt.value)} UTC",
            Patchbay.type.caption, c.textTertiary,
        )
    }
}

@Composable
private fun SetpointControl(label: String, value: Celsius?, tone: Tone, enabled: Boolean, onStep: (Double) -> Unit) {
    val c = Patchbay.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToneMarker(if (enabled) tone else Tone.NEUTRAL, Modifier.size(8.dp))
            HSpace(Space.xs + Space.xxs)
            Legend(label)
        }
        Readout(
            value?.let { Fmt.celsius(it.value) } ?: "—", "°C", Patchbay.type.valueL,
            color = if (enabled) tone.color(c) else c.textTertiary, spokenUnit = "degrees Celsius", label = label,
        )
        VSpace(Space.xs)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PButton("−0.5", { onStep(-0.5) }, Modifier.weight(1f), ButtonKind.SECONDARY, enabled = enabled)
            PButton("+0.5", { onStep(0.5) }, Modifier.weight(1f), ButtonKind.SECONDARY, enabled = enabled)
        }
    }
}

private fun snap(v: Double, cap: Capability.Thermostat): Double = Celsius(v).roundTo(cap.step).value

private fun clampHeat(v: Double, cool: Celsius?, cap: Capability.Thermostat, mode: HvacMode): Celsius {
    var x = snap(v, cap).coerceIn(cap.heatRange.start.value, cap.heatRange.endInclusive.value)
    if (mode == HvacMode.AUTO && cool != null) x = x.coerceAtMost(cool.value - cap.minimumDeadband)
    return Celsius(x)
}

private fun clampCool(v: Double, heat: Celsius?, cap: Capability.Thermostat, mode: HvacMode): Celsius {
    var x = snap(v, cap).coerceIn(cap.coolRange.start.value, cap.coolRange.endInclusive.value)
    if (mode == HvacMode.AUTO && heat != null) x = x.coerceAtLeast(heat.value + cap.minimumDeadband)
    return Celsius(x)
}

/**
 * The physical control: a vertical temperature scale. The deadband between the heat and cool
 * setpoints is shaded; the current temperature is a solid marker on the left; setpoints are
 * handles on the right that are dragged directly. Hotter is up.
 */
@Composable
fun SetpointScale(
    current: Celsius,
    heat: Celsius?,
    cool: Celsius?,
    cap: Capability.Thermostat,
    heatActive: Boolean,
    coolActive: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Celsius?, Celsius?) -> Unit,
) {
    val c = Patchbay.colors
    val values = listOfNotNull(current.value, heat?.value, cool?.value)
    val lo = floor((values.min() - 3.0) / 2.0) * 2.0
    val hi = ceil((values.max() + 3.0) / 2.0) * 2.0
    val mode = when {
        heatActive && coolActive -> HvacMode.AUTO
        heatActive -> HvacMode.HEAT
        coolActive -> HvacMode.COOL
        else -> HvacMode.OFF
    }

    BoxWithConstraints(
        modifier.semantics {
            contentDescription = "Setpoint scale"
            stateDescription = listOfNotNull(
                "current ${Fmt.celsius(current.value)} degrees",
                heat?.let { "heat to ${Fmt.celsius(it.value)}" },
                cool?.let { "cool to ${Fmt.celsius(it.value)}" },
            ).joinToString(", ")
        },
    ) {
        val h = maxHeight
        val pad = 12.dp
        val spineX = 44.dp
        fun yOf(t: Double): Dp = pad + (h - pad * 2) * ((hi - t) / (hi - lo)).toFloat()

        Canvas(
            Modifier.fillMaxSize().pointerInput(heat, cool, lo, hi, mode) {
                var target = 0 // 1 heat, 2 cool
                fun tempAt(y: Float): Double = hi - ((y - pad.toPx()) / (size.height - 2 * pad.toPx())).toDouble() * (hi - lo)
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        val t = tempAt(o.y)
                        val dh = heat?.let { kotlin.math.abs(it.value - t) } ?: Double.MAX_VALUE
                        val dc = cool?.let { kotlin.math.abs(it.value - t) } ?: Double.MAX_VALUE
                        target = when {
                            heatActive && (dh <= dc || !coolActive) -> 1
                            coolActive -> 2
                            else -> 0
                        }
                    },
                    onVerticalDrag = { change, _ ->
                        val t = tempAt(change.position.y)
                        when (target) {
                            1 -> onChange(clampHeat(t, cool, cap, mode), cool)
                            2 -> onChange(heat, clampCool(t, heat, cap, mode))
                        }
                    },
                )
            },
        ) {
            val spine = spineX.toPx()
            val hair = 1.dp.toPx()
            fun y(t: Double) = yOf(t).toPx()

            // Deadband (only meaningful in AUTO).
            if (heat != null && cool != null && heatActive && coolActive) {
                drawRect(c.band, Offset(spine, y(cool.value)), Size(size.width - spine, y(heat.value) - y(cool.value)))
            }
            // Spine and ticks every 0.5 °C, longer every 2 °C.
            drawLine(c.outline, Offset(spine, y(hi)), Offset(spine, y(lo)), hair)
            var t = lo
            while (t <= hi + 1e-9) {
                val major = kotlin.math.abs(t / 2.0 - kotlin.math.round(t / 2.0)) < 1e-9
                val len = if (major) 10.dp.toPx() else 5.dp.toPx()
                drawLine(if (major) c.outline else c.divider, Offset(spine, y(t)), Offset(spine + len, y(t)), hair)
                t += 0.5
            }
            // Setpoint handles: a 3 dp bar + a grip, in their physical colour when active.
            fun handle(v: Double, active: Boolean, color: Color) {
                val col = if (active) color else c.textTertiary
                val yy = y(v)
                drawLine(col, Offset(spine, yy), Offset(size.width - 6.dp.toPx(), yy), 3.dp.toPx())
                val g = 7.dp.toPx()
                val p = Path().apply {
                    moveTo(size.width, yy - g); lineTo(size.width - g * 1.2f, yy); lineTo(size.width, yy + g); close()
                }
                drawPath(p, col)
            }
            heat?.let { handle(it.value, heatActive, c.heat) }
            cool?.let { handle(it.value, coolActive, c.cool) }

            // Current temperature: a solid marker pointing at the spine from the left.
            val cy = y(current.value)
            val m = 8.dp.toPx()
            drawPath(
                Path().apply { moveTo(spine - 2.dp.toPx(), cy); lineTo(spine - 2.dp.toPx() - m * 1.4f, cy - m); lineTo(spine - 2.dp.toPx() - m * 1.4f, cy + m); close() },
                c.text,
            )
        }

        // Scale labels (every 2 °C), skipping the one under the current-temperature marker.
        var t = lo
        while (t <= hi + 1e-9) {
            if (kotlin.math.abs(t - current.value) > 0.6) {
                Box(Modifier.offset(x = 0.dp, y = yOf(t) - 8.dp).width(spineX - 26.dp), contentAlignment = Alignment.CenterEnd) {
                    PText(Fmt.fixed(t, 0), Patchbay.type.caption, c.textTertiary)
                }
            }
            t += 2.0
        }
        Box(Modifier.offset(x = 0.dp, y = yOf(current.value) - 26.dp).width(spineX - 4.dp), contentAlignment = Alignment.CenterEnd) {
            PText(Fmt.celsius(current.value), Patchbay.type.valueS, c.text)
        }
    }
}
