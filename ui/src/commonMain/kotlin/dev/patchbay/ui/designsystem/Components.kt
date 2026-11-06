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
import androidx.compose.ui.draw.clip
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

/** Quiet section label in sentence case. Marked as a heading for TalkBack/Narrator navigation. */
@Composable
fun Legend(text: String, modifier: Modifier = Modifier, color: Color = Patchbay.colors.textSecondary) {
    PText(text, Patchbay.type.legend, color, modifier.semantics { heading() })
}

/** A card: one tonal layer above the page, large radius. No border, no shadow, no blur, no gradient. */
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

/** A compact status pill: a tinted fill, a shape marker and text, so it doesn't rely on colour alone. */
@Composable
fun StatusTag(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val color = tone.color(c)
    Row(
        modifier
            .background(if (tone == Tone.NEUTRAL) c.raised else color.copy(alpha = 0.16f), Shapes.pill)
            .padding(horizontal = Space.m, vertical = Space.xs + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToneMarker(tone, Modifier.size(8.dp))
        Spacer(Modifier.width(Space.s))
        PText(text, Patchbay.type.legend, if (tone == Tone.NEUTRAL) c.textSecondary else color)
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
 * Segmented selector for modes (Off/Heat/Cool/Auto). A pill track in the raised layer; the selected segment is a pill
 * in the inverse colour, or in its tone colour when it carries meaning (heat, cool).
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
    val height = if (Patchbay.formFactor == FormFactor.PHONE) Space.touchTarget else 40.dp
    Row(modifier.background(c.raised, Shapes.pill).padding(Space.xs)) {
        options.forEach { opt ->
            val isSel = opt == selected
            val t = tone(opt)
            val fill = if (!isSel) null else if (t == Tone.NEUTRAL) c.inverse else t.color(c)
            val fg = if (!isSel) c.textSecondary else if (t == Tone.NEUTRAL) c.onInverse else c.onSignal
            Box(
                Modifier
                    .weight(1f)
                    .height(height - Space.s)
                    .clip(Shapes.pill)
                    .then(if (fill != null) Modifier.background(fill, Shapes.pill) else Modifier)
                    .semantics { this.selected = isSel; stateDescription = if (isSel) "selected" else "" }
                    .clickable(role = Role.Tab) { onSelect(opt) },
                contentAlignment = Alignment.Center,
            ) {
                PText(label(opt), Patchbay.type.bodyStrong, fg)
            }
        }
    }
}

enum class ButtonKind { PRIMARY, SECONDARY, DESTRUCTIVE }

/**
 * Pill button, no outline. Primary is the inverse pair (a light pill with dark text on dark themes), secondary is a
 * raised pill, destructive is a raised pill with alarm-coloured text.
 */
@Composable
fun PButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    enabled: Boolean = true,
) {
    val c = Patchbay.colors
    val fill = when {
        !enabled -> c.raised.copy(alpha = 0.6f)
        kind == ButtonKind.PRIMARY -> c.inverse
        else -> c.raised
    }
    val fg = when {
        !enabled -> c.textTertiary
        kind == ButtonKind.PRIMARY -> c.onInverse
        kind == ButtonKind.DESTRUCTIVE -> c.alarm
        else -> c.text
    }
    val minH = if (Patchbay.formFactor == FormFactor.PHONE) Space.touchTarget else 40.dp
    Box(
        modifier
            .defaultMinSize(minWidth = 88.dp, minHeight = minH)
            .clip(Shapes.pill)
            .background(fill, Shapes.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        PText(text, Patchbay.type.bodyStrong, fg)
    }
}

/** A labelled switch in the familiar rounded style. The state is in the knob position and the semantics, not colour alone. */
@Composable
fun PToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val minH = if (Patchbay.formFactor == FormFactor.PHONE) Space.touchTarget else 40.dp
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minH)
            .clip(Shapes.control)
            .clickable(role = Role.Switch) { onChange(!on) }
            .semantics { stateDescription = if (on) "on" else "off" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PText(label, Patchbay.type.body, c.text)
        Box(
            Modifier.size(width = 52.dp, height = 32.dp)
                .background(if (on) c.active else c.raised, Shapes.pill)
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(26.dp).background(if (on) c.onSignal else c.outline, Shapes.pill))
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
