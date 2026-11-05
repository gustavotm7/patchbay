package dev.patchbay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.Trend
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.Contrast
import dev.patchbay.ui.designsystem.DeviceGlyph
import dev.patchbay.ui.designsystem.GlyphState
import dev.patchbay.ui.designsystem.HSpace
import dev.patchbay.ui.designsystem.Hairline
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.PToggle
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.PatchbayColors
import dev.patchbay.ui.designsystem.Readout
import dev.patchbay.ui.designsystem.Segmented
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import dev.patchbay.ui.designsystem.ValueRow
import kotlin.math.roundToInt

/** A living reference sheet of the design system, rendered by the screenshot tests. */
@Composable
fun DesignSheet() {
    val c = Patchbay.colors
    Column(Modifier.fillMaxSize().background(c.background).padding(Space.xxl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PText("Patchbay design system", Patchbay.type.title)
            HSpace(Space.m)
            StatusTag(c.name, Tone.NEUTRAL)
        }
        PText("Control Room — neutral baseline, colour only for meaning. IBM Plex Sans / Plex Mono.", Patchbay.type.body, c.textSecondary)
        VSpace(Space.xl)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            Column(Modifier.weight(1.1f)) {
                Panel(Modifier.fillMaxWidth()) {
                    Legend("Colour roles · contrast vs surface")
                    VSpace(Space.s)
                    roles(c).forEach { (name, color, note) -> Swatch(name, color, c, note) }
                }
            }
            Column(Modifier.weight(1f)) {
                Panel(Modifier.fillMaxWidth()) {
                    Legend("Type scale")
                    VSpace(Space.s)
                    TypeSample("valueXL 44", Patchbay.type.valueXL, "20.6")
                    TypeSample("valueL 28", Patchbay.type.valueL, "24.5")
                    TypeSample("valueM 17", Patchbay.type.valueM, "1 234.5")
                    TypeSample("title 20", Patchbay.type.title, "Thermostat")
                    TypeSample("body 14", Patchbay.type.body, "Heat and cool setpoints")
                    TypeSample("legend 11", Patchbay.type.legend, "SETPOINTS")
                    TypeSample("caption 12", Patchbay.type.caption, "updated 19:30 UTC")
                    TypeSample("mono 12", Patchbay.type.mono, "HMS_0300_0100_0001 · 0O0")
                    VSpace(Space.s)
                    PText("Tabular figures: digits keep their width as values change", Patchbay.type.caption, c.textTertiary)
                    Row { listOf("11.1", "88.8", "20.6", "47.0").forEach { PText(it, Patchbay.type.valueM, modifier = Modifier.width(64.dp)) } }
                }
                VSpace(Space.l)
                Panel(Modifier.fillMaxWidth()) {
                    Legend("Spacing (4 dp grid) · shape")
                    VSpace(Space.s)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        listOf(Space.xs, Space.s, Space.m, Space.l, Space.xl, Space.xxl, Space.xxxl).forEach { d ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(d).background(c.band).border(Space.hair, c.outline))
                                PText("${d.value.roundToInt()}", Patchbay.type.caption, c.textTertiary)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1.2f)) {
                Panel(Modifier.fillMaxWidth()) {
                    Legend("Device glyphs · on / off / sensor / unmanaged")
                    VSpace(Space.s)
                    val kinds = listOf(
                        DeviceKind.LIGHT, DeviceKind.TV, DeviceKind.MEDIA_RECEIVER, DeviceKind.SPEAKER, DeviceKind.THERMOSTAT,
                        DeviceKind.PRINTER_3D, DeviceKind.AIR_PURIFIER, DeviceKind.SMOKE_CO_ALARM, DeviceKind.APPLIANCE,
                        DeviceKind.MICROCONTROLLER, DeviceKind.UNKNOWN,
                    )
                    kinds.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            row.forEach { k ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                                    Row {
                                        DeviceGlyph(k, GlyphState.ON, Modifier.size(24.dp), emitted = if (k == DeviceKind.LIGHT) Color(0xFFFFB46B) else null)
                                        DeviceGlyph(k, GlyphState.OFF, Modifier.size(24.dp))
                                    }
                                    Row {
                                        DeviceGlyph(k, GlyphState.PASSIVE, Modifier.size(24.dp))
                                        DeviceGlyph(k, GlyphState.UNMANAGED, Modifier.size(24.dp))
                                    }
                                    PText(k.name.lowercase().replace('_', ' '), Patchbay.type.caption, c.textTertiary, maxLines = 1)
                                }
                            }
                        }
                        VSpace(Space.s)
                    }
                }
                VSpace(Space.l)
                Panel(Modifier.fillMaxWidth()) {
                    Legend("Signals · shape + colour + text")
                    VSpace(Space.s)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        StatusTag("Idle", Tone.NEUTRAL)
                        StatusTag("Printing", Tone.ACTIVE)
                        StatusTag("Heating", Tone.HEAT)
                        StatusTag("Cooling", Tone.COOL)
                    }
                    VSpace(Space.s)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        StatusTag("Cloud-only", Tone.ATTENTION)
                        StatusTag("Trust changed", Tone.ALARM)
                    }
                    VSpace(Space.l)
                    Legend("Controls")
                    VSpace(Space.s)
                    Segmented(listOf("Off", "Heat", "Cool", "Auto"), "Auto", { it }, {}, Modifier.fillMaxWidth())
                    VSpace(Space.s)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        PButton("Pause", {}, kind = ButtonKind.PRIMARY)
                        PButton("Details", {})
                        PButton("Stop…", {}, kind = ButtonKind.DESTRUCTIVE)
                        PButton("Disabled", {}, enabled = false)
                    }
                    VSpace(Space.s)
                    PToggle("Chamber light", true, {})
                    Hairline()
                    ValueRow("Humidity", "38", "%", trend = Trend(listOf(36.0, 37.5, 39.0, 41.0, 40.0, 38.5, 38.0), 1), detail = "24 h")
                    VSpace(Space.s)
                    Readout("20.6", "°C", Patchbay.type.valueL)
                }
            }
        }
    }
}

private fun roles(c: PatchbayColors): List<Triple<String, Color, String>> = listOf(
    Triple("background", c.background, "page"),
    Triple("surface", c.surface, "panels"),
    Triple("raised", c.raised, "controls, selection"),
    Triple("outline", c.outline, "boundaries ≥ 3:1"),
    Triple("divider", c.divider, "decorative only"),
    Triple("text", c.text, "primary"),
    Triple("textSecondary", c.textSecondary, "labels"),
    Triple("textTertiary", c.textTertiary, "units, captions"),
    Triple("active", c.active, "output energised"),
    Triple("heat", c.heat, "heating demand"),
    Triple("cool", c.cool, "cooling demand"),
    Triple("attention", c.attention, "needs a look"),
    Triple("alarm", c.alarm, "fault / abnormal"),
)

@Composable
private fun Swatch(name: String, color: Color, c: PatchbayColors, note: String) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 36.dp, height = 22.dp).background(color, Shapes.chip).border(Space.hair, c.divider, Shapes.chip))
        HSpace(Space.m)
        Column(Modifier.weight(1f)) {
            PText(name, Patchbay.type.bodyStrong, maxLines = 1)
            PText(note, Patchbay.type.caption, c.textTertiary, maxLines = 1)
        }
        PText(hex(color), Patchbay.type.mono, c.textSecondary)
        HSpace(Space.m)
        val r = Contrast.ratio(color, c.surface)
        PText(if (r < 1.05) "—" else "${(r * 10).roundToInt() / 10.0}:1", Patchbay.type.valueS, c.text, Modifier.width(52.dp))
    }
}

@Composable
private fun TypeSample(label: String, style: TextStyle, sample: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
        PText(label, Patchbay.type.caption, Patchbay.colors.textTertiary, Modifier.width(88.dp))
        PText(sample, style, maxLines = 1)
    }
}

private fun hex(c: Color): String {
    fun h(v: Float) = (v * 255).roundToInt().toString(16).padStart(2, '0').uppercase()
    return "#${h(c.red)}${h(c.green)}${h(c.blue)}"
}
