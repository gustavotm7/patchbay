package dev.patchbay.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import dev.patchbay.core.model.DeviceKind

/** How a device glyph is drawn. Fill vs outline is the primary on/off signal (shape, not colour). */
enum class GlyphState {
    /** Output active: filled. */
    ON,

    /** Reachable, output off: outline. */
    OFF,

    /** A sensor or passive device: outline with a centre dot. */
    PASSIVE,

    /** Cloud-only or unknown: dashed outline, never filled (we don't control it). */
    UNMANAGED,
}

/**
 * Geometric device glyphs — no icon font, no emoji. Each kind has a distinct silhouette so the
 * floor plan stays legible in greyscale. [emitted] is a light's actual output colour, shown as fill.
 */
@Composable
fun DeviceGlyph(
    kind: DeviceKind,
    state: GlyphState,
    modifier: Modifier = Modifier,
    emitted: Color? = null,
    tint: Color = Patchbay.colors.text,
) {
    val c = Patchbay.colors
    Canvas(modifier) {
        val sw = 1.5.dp.toPx()
        val dashed = state == GlyphState.UNMANAGED
        val stroke = Stroke(sw, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null)
        val strokeColor = if (dashed) c.textSecondary else tint
        val fillColor = emitted ?: tint
        val filled = state == GlyphState.ON
        val s = size.minDimension
        val o = Offset((size.width - s) / 2, (size.height - s) / 2)

        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1.dp.toPx()) {
            if (filled) drawRoundRect(fillColor, o + Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r))
            drawRoundRect(strokeColor, o + Offset(x * s, y * s), Size(w * s, h * s), CornerRadius(r), style = stroke)
        }

        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = filled) {
            if (fill) drawCircle(fillColor, r * s, o + Offset(cx * s, cy * s))
            drawCircle(strokeColor, r * s, o + Offset(cx * s, cy * s), style = stroke)
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = if (filled) c.onSignal else strokeColor) =
            drawLine(color, o + Offset(x1 * s, y1 * s), o + Offset(x2 * s, y2 * s), sw)

        when (kind) {
            DeviceKind.LIGHT -> {
                circle(0.5f, 0.5f, 0.32f)
                if (filled) drawCircle(fillColor.copy(alpha = 0.25f), 0.48f * s, o + Offset(0.5f * s, 0.5f * s))
            }
            DeviceKind.TV -> {
                rect(0.08f, 0.2f, 0.84f, 0.52f)
                line(0.35f, 0.86f, 0.65f, 0.86f, strokeColor)
            }
            DeviceKind.MEDIA_RECEIVER -> {
                rect(0.18f, 0.36f, 0.64f, 0.28f, r = 0.14f * s)
            }
            DeviceKind.SPEAKER -> {
                circle(0.5f, 0.5f, 0.34f)
                circle(0.5f, 0.5f, 0.1f, fill = false)
            }
            DeviceKind.THERMOSTAT -> {
                circle(0.5f, 0.5f, 0.36f, fill = false)
                // Scale ticks around the dial: reads as an instrument, not a light.
                for (i in 0 until 8) {
                    val a = (i / 8.0) * 2 * kotlin.math.PI
                    val x1 = 0.5f + 0.24f * kotlin.math.cos(a).toFloat()
                    val y1 = 0.5f + 0.24f * kotlin.math.sin(a).toFloat()
                    val x2 = 0.5f + 0.32f * kotlin.math.cos(a).toFloat()
                    val y2 = 0.5f + 0.32f * kotlin.math.sin(a).toFloat()
                    line(x1, y1, x2, y2, strokeColor)
                }
            }
            DeviceKind.PRINTER_3D -> {
                rect(0.14f, 0.12f, 0.72f, 0.76f)
                line(0.24f, 0.36f, 0.76f, 0.36f) // gantry
                line(0.5f, 0.36f, 0.5f, 0.5f) // nozzle
                line(0.28f, 0.74f, 0.72f, 0.74f) // bed
            }
            DeviceKind.AIR_PURIFIER -> {
                rect(0.24f, 0.1f, 0.52f, 0.8f)
                line(0.38f, 0.28f, 0.38f, 0.72f)
                line(0.5f, 0.28f, 0.5f, 0.72f)
                line(0.62f, 0.28f, 0.62f, 0.72f)
            }
            DeviceKind.SMOKE_CO_ALARM -> {
                circle(0.5f, 0.5f, 0.38f, fill = false)
                circle(0.5f, 0.5f, 0.2f, fill = false)
            }
            DeviceKind.APPLIANCE -> {
                rect(0.16f, 0.12f, 0.68f, 0.76f)
                circle(0.5f, 0.56f, 0.2f, fill = false)
            }
            DeviceKind.MICROCONTROLLER -> {
                rect(0.28f, 0.28f, 0.44f, 0.44f)
                for (i in 0 until 3) {
                    val p = 0.36f + i * 0.14f
                    line(p, 0.12f, p, 0.28f, strokeColor)
                    line(p, 0.72f, p, 0.88f, strokeColor)
                }
            }
            DeviceKind.CAMERA -> {
                rect(0.12f, 0.3f, 0.56f, 0.4f)
                // lens housing on the right
                val p = Path().apply {
                    moveTo(o.x + 0.68f * s, o.y + 0.42f * s); lineTo(o.x + 0.88f * s, o.y + 0.32f * s)
                    lineTo(o.x + 0.88f * s, o.y + 0.68f * s); lineTo(o.x + 0.68f * s, o.y + 0.58f * s); close()
                }
                drawPath(p, strokeColor, style = stroke)
            }
            DeviceKind.LOCK -> {
                rect(0.22f, 0.44f, 0.56f, 0.44f)
                // shackle
                drawArc(strokeColor, 180f, 180f, false, o + Offset(0.32f * s, 0.18f * s), Size(0.36f * s, 0.5f * s), style = stroke)
            }
            DeviceKind.NETWORK_INFRA, DeviceKind.UNKNOWN -> {
                val p = Path().apply {
                    moveTo(o.x + 0.5f * s, o.y + 0.12f * s)
                    lineTo(o.x + 0.88f * s, o.y + 0.5f * s)
                    lineTo(o.x + 0.5f * s, o.y + 0.88f * s)
                    lineTo(o.x + 0.12f * s, o.y + 0.5f * s)
                    close()
                }
                drawPath(p, strokeColor, style = stroke)
            }
        }
        if (state == GlyphState.PASSIVE) drawCircle(strokeColor, 1.6.dp.toPx(), center)
    }
}

/** Hatched fill used for cloud-only areas/badges, so "unmanaged" reads without colour. */
fun DrawScope.hatch(color: Color, spacing: Float, strokeWidth: Float) {
    clipRect {
        var x = -size.height
        while (x < size.width) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth)
            x += spacing
        }
    }
}
