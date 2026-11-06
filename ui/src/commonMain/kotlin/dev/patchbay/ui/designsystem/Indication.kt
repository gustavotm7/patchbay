package dev.patchbay.ui.designsystem

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Press/hover feedback is a flat luminance shift, not a ripple. Keyboard focus is a 2 dp ring in the
 * primary text colour, visible on every theme (desktop is keyboard-first).
 */
internal class FocusAndPressIndication(private val colors: PatchbayColors) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode = IndicationNode(interactionSource, colors)

    override fun equals(other: Any?): Boolean = other is FocusAndPressIndication && other.colors == colors

    override fun hashCode(): Int = colors.hashCode()

    private class IndicationNode(
        private val source: InteractionSource,
        private val colors: PatchbayColors,
    ) : Modifier.Node(), DrawModifierNode {
        private var pressed = 0
        private var hovered = 0
        private var focused = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = (pressed - 1).coerceAtLeast(0)
                        is HoverInteraction.Enter -> hovered++
                        is HoverInteraction.Exit -> hovered = (hovered - 1).coerceAtLeast(0)
                        is FocusInteraction.Focus -> focused++
                        is FocusInteraction.Unfocus -> focused = (focused - 1).coerceAtLeast(0)
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            // Rounded like the controls: a pill for short elements, a 22 dp card radius for tall ones.
            val r = androidx.compose.ui.geometry.CornerRadius(minOf(size.minDimension / 2f, 22.dp.toPx()))
            if (pressed > 0) drawRoundRect(colors.text.copy(alpha = 0.12f), cornerRadius = r)
            else if (hovered > 0) drawRoundRect(colors.text.copy(alpha = 0.06f), cornerRadius = r)
            if (focused > 0) {
                val w = 2.dp.toPx()
                drawRoundRect(colors.text, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = r, style = Stroke(w))
            }
        }
    }
}
