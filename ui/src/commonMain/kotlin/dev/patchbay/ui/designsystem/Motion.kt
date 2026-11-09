package dev.patchbay.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Motion for state change, never decoration (DESIGN_SPEC v2). Everything here takes its duration from [Patchbay.motion], so
 * reduced motion turns every transition into an instant change and stops the spinner.
 */

/** True when the user asked for no animation. */
val reducedMotion: Boolean @Composable get() = Patchbay.motion.medium == 0

/** A colour that eases to [target] when it changes (on/off, selected/unselected). The first frame is the target itself, so static renders stay deterministic. */
@Composable
fun animatedColor(target: Color): Color {
    val m = Patchbay.motion
    val v by animateColorAsState(target, tween(m.medium, easing = m.easing), label = "colour")
    return v
}

/** A float that eases to [target]. */
@Composable
fun animatedFloat(target: Float): Float {
    val m = Patchbay.motion
    val v by animateFloatAsState(target, tween(m.medium, easing = m.easing), label = "float")
    return v
}

/** Shrinks the element a few percent while it is pressed and springs back on release, so a tap feels physical. Pass the same [source] to `clickable`. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, amount: Float = 0.97f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val still = reducedMotion
    val scale by animateFloatAsState(
        if (pressed && !still) amount else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A small spinning arc for "working on it" (scanning). Static under reduced motion. */
@Composable
fun BusyArc(color: Color, modifier: Modifier = Modifier) {
    val still = reducedMotion
    val turn = if (still) 0f else {
        val t = rememberInfiniteTransition(label = "busy")
        val v by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn")
        v
    }
    Canvas(modifier) {
        val w = 2.dp.toPx()
        drawArc(
            color, startAngle = turn, sweepAngle = 270f, useCenter = false,
            topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}
