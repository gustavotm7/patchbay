package dev.patchbay.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.model.Rgb
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.HSpace
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
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Light control, mirroring the physics (DESIGN_SPEC §5): a 2D pad where x is colour temperature
 * (warm → cool, drawn in the real black-body colours) and y is brightness. A hue strip appears only
 * for lights with RGB output. Values are always shown with units.
 */
@Composable
fun LightPanel(live: LiveDevice, onCommand: (Command) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val d = live.device
    val s = live.snapshot
    val on = s?.state<CapabilityState.PowerState>()?.on ?: false
    val level = s?.state<CapabilityState.BrightnessState>()?.level?.value ?: 50.0
    val kelvin = s?.state<CapabilityState.ColourTemperatureState>()?.kelvin?.value
    val rgb = s?.state<CapabilityState.RgbState>()?.colour
    val cct = d.capability<Capability.ColourTemperature>()
    val reach = s?.reachability

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PText(d.name, Patchbay.type.title)
                PText(listOfNotNull(d.vendor, d.model, DeviceViews.controlPathLabel(d.controlPath)).joinToString(" · "), Patchbay.type.caption, c.textSecondary)
            }
            when {
                reach == Reachability.OFFLINE -> StatusTag("Offline", Tone.ALARM)
                reach == Reachability.DEGRADED -> StatusTag("No reply", Tone.ATTENTION)
                s == null -> StatusTag("Connecting", Tone.NEUTRAL)
                on -> StatusTag("On", Tone.ACTIVE)
                else -> StatusTag("Off", Tone.NEUTRAL)
            }
        }
        VSpace(Space.l)
        PToggle("Power", on, { onCommand(Command.SetPower(it)) })
        VSpace(Space.m)

        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Legend("Brightness")
                Readout(Fmt.percent(level), "%", Patchbay.type.valueL, spokenUnit = "percent", label = "Brightness")
            }
            Column(Modifier.weight(1f)) {
                Legend(if (kelvin != null || rgb == null) "Colour temperature" else "Colour")
                if (kelvin != null || rgb == null) {
                    Readout(kelvin?.toString() ?: "—", "K", Patchbay.type.valueL, spokenUnit = "kelvin", label = "Colour temperature")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).border(Space.hair, c.outline, Shapes.chip)) {
                            Canvas(Modifier.size(28.dp)) { drawRect(Color(rgb.r, rgb.g, rgb.b)) }
                        }
                        HSpace(Space.s)
                        PText("RGB ${rgb.r} ${rgb.g} ${rgb.b}", Patchbay.type.mono, c.textSecondary)
                    }
                }
            }
        }
        VSpace(Space.m)

        if (cct != null) {
            LightPad(
                kelvin = kelvin ?: cct.warmest.value,
                level = level,
                warmest = cct.warmest.value,
                coolest = cct.coolest.value,
                showMarker = kelvin != null,
                enabled = reach != Reachability.OFFLINE,
                onChange = { k, b ->
                    onCommand(Command.SetColourTemperature(Kelvin(k)))
                    onCommand(Command.SetBrightness(Percent(b.toDouble())))
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(1.6f),
            )
            Row(Modifier.fillMaxWidth()) {
                PText("${cct.warmest.value} K", Patchbay.type.caption, c.textTertiary, Modifier.weight(1f))
                PText("${cct.coolest.value} K", Patchbay.type.caption, c.textTertiary)
            }
        }

        if (d.capability<Capability.RgbColour>() != null) {
            VSpace(Space.m)
            Legend("Colour")
            VSpace(Space.xs)
            HueStrip(onPick = { onCommand(Command.SetColour(it)) }, modifier = Modifier.fillMaxWidth().height(40.dp))
        }
        VSpace(Space.m)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(10, 40, 100).forEach { b ->
                PButton("$b %", { onCommand(Command.SetBrightness(Percent(b.toDouble()))) }, Modifier.weight(1f), ButtonKind.SECONDARY)
            }
        }
    }
}

/**
 * The brightness × colour-temperature pad. Tapping or dragging sets both at once; the commands are
 * sent at drag end and at most every ~150 ms during a drag (the adapter paces further).
 */
@Composable
fun LightPad(
    kelvin: Int,
    level: Double,
    warmest: Int,
    coolest: Int,
    showMarker: Boolean,
    enabled: Boolean,
    onChange: (kelvin: Int, brightness: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Patchbay.colors
    var local by remember(kelvin, level) { mutableStateOf<Pair<Float, Float>?>(null) }
    val latest by rememberUpdatedState(onChange)

    fun toValues(fx: Float, fy: Float): Pair<Int, Int> {
        val k = (warmest + (coolest - warmest) * fx.coerceIn(0f, 1f)).roundToInt() / 100 * 100
        val b = (100 - 99 * fy.coerceIn(0f, 1f)).roundToInt().coerceIn(1, 100)
        return k.coerceIn(warmest, coolest) to b
    }

    val fxNow = ((kelvin - warmest).toFloat() / (coolest - warmest)).coerceIn(0f, 1f)
    val fyNow = ((100.0 - level) / 99.0).toFloat().coerceIn(0f, 1f)
    val shown = local ?: (fxNow to fyNow)
    val (kShown, bShown) = toValues(shown.first, shown.second)

    Box(
        modifier
            .border(Space.hair, c.outline, Shapes.control)
            .semantics {
                contentDescription = "Brightness and colour temperature pad"
                stateDescription = "$bShown percent, $kShown kelvin"
            },
    ) {
        Canvas(
            Modifier.matchParentSize().pointerInput(enabled, warmest, coolest) {
                if (!enabled) return@pointerInput
                val clock = TimeSource.Monotonic.markNow()
                var lastSent = -1_000L
                fun at(p: Offset) = (p.x / size.width) to (p.y / size.height)
                fun send(f: Pair<Float, Float>, force: Boolean) {
                    val now = clock.elapsedNow().inWholeMilliseconds
                    if (force || now - lastSent > 150) {
                        lastSent = now
                        val (k, b) = toValues(f.first, f.second)
                        latest(k, b)
                    }
                }
                detectDragGestures(
                    onDragStart = { p -> local = at(p) },
                    onDragEnd = { local?.let { send(it, force = true) } },
                    onDrag = { change, _ -> change.consume(); local = at(change.position).also { send(it, force = false) } },
                )
            }.pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { p ->
                    val f = (p.x / size.width) to (p.y / size.height)
                    local = f
                    val (k, b) = toValues(f.first, f.second)
                    latest(k, b)
                }
            },
        ) {
            // Real black-body colours along x; darkening toward the bottom shows brightness.
            val stops = (0..8).map { i -> DeviceViews.kelvinToColor(warmest + (coolest - warmest) * i / 8) }
            drawRect(Brush.horizontalGradient(stops))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (c.isDark) 0.85f else 0.7f))))
            if (c.name == "night") drawRect(c.background.copy(alpha = 0.55f)) // keep a dark bedroom dark
            if (!enabled) drawRect(c.background.copy(alpha = 0.6f))
            if (showMarker || local != null) {
                val center = Offset(shown.first * size.width, shown.second * size.height)
                drawCircle(Color.Black, 11.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 11.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** A hue strip at full saturation and value. Lights only show it when they have RGB output. */
@Composable
fun HueStrip(onPick: (Rgb) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val latest by rememberUpdatedState(onPick)
    Box(modifier.border(Space.hair, c.outline, Shapes.control).semantics { contentDescription = "Colour picker" }) {
        Canvas(
            Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures { p -> latest(hue(p.x / size.width)) }
            },
        ) {
            drawRect(Brush.horizontalGradient((0..12).map { i -> hue(i / 12f).let { Color(it.r, it.g, it.b) } }))
            if (c.name == "night") drawRect(c.background.copy(alpha = 0.55f))
        }
    }
}

/** HSV(h, 1, 1) → sRGB. */
internal fun hue(f: Float): Rgb {
    val h = (f.coerceIn(0f, 1f) * 6f)
    val x = 1f - kotlin.math.abs(h % 2f - 1f)
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(1f, x, 0f)
        1 -> Triple(x, 1f, 0f)
        2 -> Triple(0f, 1f, x)
        3 -> Triple(0f, x, 1f)
        4 -> Triple(x, 0f, 1f)
        else -> Triple(1f, 0f, x)
    }
    return Rgb((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
}
