package dev.patchbay.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patchbay.core.model.Trend

/** All text goes through here so colour always comes from a role. */
@Composable
fun PText(
    text: String,
    style: TextStyle,
    color: Color = Patchbay.colors.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Uppercase instrument legend. Marked as a heading for TalkBack/Narrator navigation. */
@Composable
fun Legend(text: String, modifier: Modifier = Modifier, color: Color = Patchbay.colors.textSecondary) {
    PText(text.uppercase(), Patchbay.type.legend, color, modifier.semantics { heading() })
}

/** A flat panel with a hairline outline. No shadows, no blur, no gradients. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = Space.l,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Patchbay.colors
    Column(
        modifier
            .background(c.surface, Shapes.panel)
            .border(Space.hair, c.divider, Shapes.panel)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Patchbay.colors.divider) {
    Box(modifier.fillMaxWidth().height(Space.hair).background(color))
}

/**
 * A value with its unit, always. The unit is smaller and tertiary so the number dominates.
 * The accessibility label reads "20.6 degrees Celsius" rather than "20.6 °C".
 */
@Composable
fun Readout(
    value: String,
    unit: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Patchbay.colors.text,
    spokenUnit: String = unit,
    label: String? = null,
) {
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(label, "$value $spokenUnit").joinToString(", ")
        },
        verticalAlignment = Alignment.Bottom,
    ) {
        PText(value, style, color)
        Spacer(Modifier.width(Space.xxs))
        PText(unit, Patchbay.type.unit, Patchbay.colors.textTertiary, Modifier.padding(bottom = unitBaselineNudge(style)))
    }
}

@Composable
private fun unitBaselineNudge(style: TextStyle): Dp = when {
    style.fontSize.value >= 40f -> 8.dp
    style.fontSize.value >= 26f -> 4.dp
    style.fontSize.value >= 16f -> 2.dp
    else -> 0.dp
}

/** A labelled value row used in dense lists: LEGEND ......... value unit [sparkline]. */
@Composable
fun ValueRow(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    trend: Trend? = null,
    trendColor: Color = Patchbay.colors.textSecondary,
    valueColor: Color = Patchbay.colors.text,
    spokenUnit: String = unit,
    detail: String? = null,
) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PText(label, Patchbay.type.body, Patchbay.colors.textSecondary, maxLines = 1)
            if (detail != null) PText(detail, Patchbay.type.caption, Patchbay.colors.textTertiary, maxLines = 1)
        }
        if (trend != null) {
            Sparkline(trend, Modifier.width(64.dp).height(20.dp), trendColor)
            Spacer(Modifier.width(Space.m))
        }
        Readout(value, unit, Patchbay.type.valueM, color = valueColor, spokenUnit = spokenUnit, label = label)
    }
}

/**
 * An inline trend line. Neutral by default; it is context, not a signal. The last point is marked
 * so the eye connects it to the readout beside it.
 */
@Composable
fun Sparkline(trend: Trend, modifier: Modifier = Modifier, color: Color = Patchbay.colors.textSecondary) {
    val values = trend.values
    Canvas(modifier.semantics { contentDescription = "trend" }) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val pad = 2.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun pt(i: Int) = Offset(pad + w * i / (values.size - 1), pad + h * (1f - ((values[i] - lo) / span).toFloat()))
        val path = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(path, color, style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = 2.dp.toPx(), center = pt(values.lastIndex))
    }
}

/** A compact status tag: text + a shape marker, so it doesn't rely on colour alone. */
@Composable
fun StatusTag(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val color = tone.color(c)
    Row(
        modifier
            .border(Space.hair, if (tone == Tone.NEUTRAL) c.outline else color, Shapes.chip)
            .padding(horizontal = Space.s, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneMarker(tone, Modifier.size(8.dp))
        Spacer(Modifier.width(Space.xs + Space.xxs))
        PText(text.uppercase(), Patchbay.type.legend, if (tone == Tone.NEUTRAL) c.textSecondary else color)
    }
}

/**
 * Tones map to meaning. Each has a distinct marker shape, so meaning survives greyscale and
 * colour-vision deficiency: ■ active, ▲ attention, ◆ alarm, ○ neutral.
 */
enum class Tone {
    NEUTRAL, ACTIVE, HEAT, COOL, ATTENTION, ALARM;

    fun color(c: PatchbayColors): Color = when (this) {
        NEUTRAL -> c.textSecondary
        ACTIVE -> c.active
        HEAT -> c.heat
        COOL -> c.cool
        ATTENTION -> c.attention
        ALARM -> c.alarm
    }
}

@Composable
fun ToneMarker(tone: Tone, modifier: Modifier = Modifier) {
    val color = tone.color(Patchbay.colors)
    Canvas(modifier) {
        val s = size.minDimension
        when (tone) {
            Tone.NEUTRAL -> drawCircle(color, s / 2 - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
            Tone.ACTIVE, Tone.HEAT, Tone.COOL -> drawRect(color)
            Tone.ATTENTION -> drawPath(
                Path().apply { moveTo(s / 2, 0f); lineTo(s, s); lineTo(0f, s); close() }, color,
            )
            Tone.ALARM -> drawPath(
                Path().apply { moveTo(s / 2, 0f); lineTo(s, s / 2); lineTo(s / 2, s); lineTo(0f, s / 2); close() }, color,
            )
        }
    }
}

/**
 * Segmented selector for modes (Off/Heat/Cool/Auto). The selected segment is filled with the
 * raised colour and carries a 2 dp underline in its tone; unselected segments are outline-only.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tone: (T) -> Tone = { Tone.NEUTRAL },
) {
    val c = Patchbay.colors
    val height = if (Patchbay.formFactor == FormFactor.PHONE) Space.touchTarget else 36.dp
    Row(modifier.border(Space.hair, c.outline, Shapes.control)) {
        options.forEachIndexed { i, opt ->
            val isSel = opt == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .background(if (isSel) c.raised else c.surface)
                    .semantics { this.selected = isSel; stateDescription = if (isSel) "selected" else "" }
                    .clickable(role = Role.Tab) { onSelect(opt) },
                contentAlignment = Alignment.Center,
            ) {
                PText(label(opt), Patchbay.type.bodyStrong, if (isSel) c.text else c.textSecondary)
                if (isSel) {
                    val t = tone(opt)
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                            .background(if (t == Tone.NEUTRAL) c.text else t.color(c)),
                    )
                }
            }
            if (i < options.lastIndex) Box(Modifier.width(Space.hair).height(height).background(c.outline))
        }
    }
}

enum class ButtonKind { PRIMARY, SECONDARY, DESTRUCTIVE }

/** Instrument-style button: rectangular, outlined, label in medium weight. */
@Composable
fun PButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
) {
    val c = Patchbay.colors
    val fg = when {
        !enabled -> c.textTertiary
        kind == ButtonKind.DESTRUCTIVE -> c.alarm
        else -> c.text
    }
    val border = when (kind) {
        ButtonKind.PRIMARY -> c.text
        ButtonKind.SECONDARY -> c.outline
        ButtonKind.DESTRUCTIVE -> c.alarm
    }
    val minH = if (Patchbay.formFactor == FormFactor.PHONE) Space.touchTarget else 36.dp
    Box(
        modifier
            .defaultMinSize(minWidth = 88.dp, minHeight = minH)
            .background(if (kind == ButtonKind.PRIMARY) c.raised else c.surface, Shapes.control)
            .border(Space.hair, border, Shapes.control)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) {
        PText(text, Patchbay.type.bodyStrong, fg)
    }
}

/** A labelled on/off switch drawn as a two-position toggle, with the state spelled out. */
@Composable
fun PToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val minH = if (Patchbay.formFactor == FormFactor.PHONE) Space.touchTarget else 32.dp
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minH)
            .clickable(role = Role.Switch) { onChange(!on) }
            .semantics { stateDescription = if (on) "on" else "off" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PText(label, Patchbay.type.body, c.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PText(if (on) "ON" else "OFF", Patchbay.type.legend, if (on) c.active else c.textTertiary)
            Spacer(Modifier.width(Space.s))
            Box(
                Modifier.size(width = 36.dp, height = 20.dp)
                    .border(Space.hair, if (on) c.active else c.outline, Shapes.chip)
                    .padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(14.dp).background(if (on) c.active else c.outline, Shapes.chip))
            }
        }
    }
}

@Composable
fun RowScope.Fill() = Spacer(Modifier.weight(1f))

@Composable
fun ColumnScope.VFill() = Spacer(Modifier.weight(1f))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
