package dev.patchbay.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Colour roles (docs/design/DESIGN_SPEC.md §2, ADR 0009).
 *
 * Rule: surfaces and text are neutral. Depth comes from tonal layers (background, surface, raised), not borders or
 * shadows. Saturated colour appears only when it means something (active output, heat or cool, attention, alarm) or
 * when showing a device's real emitted colour. Selection and "on" use the [inverse] pair (a light tile with dark text
 * in dark themes), which reads at a glance without colour. Colour is never the only signal.
 *
 * All text roles meet WCAG 2.2 AA (at least 4.5:1) on background, surface and raised; [outline] meets the 3:1
 * non-text minimum. Enforced by ContrastTest.
 */
@Immutable
data class PatchbayColors(
    val name: String,
    val isDark: Boolean,
    /** Window/page background (the base layer). */
    val background: Color,
    /** Cards and tiles (the elevated layer). */
    val surface: Color,
    /** Controls and inset wells inside a card (the highest layer). */
    val raised: Color,
    /** Control boundaries that must be visible (inputs, switch track off state). At least 3:1. */
    val outline: Color,
    /** Hairline separators inside lists. Decorative only. */
    val divider: Color,
    val text: Color,
    val textSecondary: Color,
    /** Units, captions. Still AA. */
    val textTertiary: Color,
    /** An output is energised/active (light on, playback running, print in progress). */
    val active: Color,
    /** Heating demand / heat setpoint. Physical meaning only. */
    val heat: Color,
    /** Cooling demand / cool setpoint. Physical meaning only. */
    val cool: Color,
    /** Needs attention: unknown device, cloud-only exposure, low filament. */
    val attention: Color,
    /** Abnormal/fault: HMS fault, offline, trust changed. */
    val alarm: Color,
    /** Fill behind the thermostat deadband and other neutral bands. */
    val band: Color,
    /** Text/glyph colour on top of [active], [heat], [cool], [attention] or [alarm] fills. */
    val onSignal: Color,
    /** Fill for the primary button, an "on" tile and the selected segment. */
    val inverse: Color,
    /** Text/glyph colour on top of [inverse]. */
    val onInverse: Color,
)

object PatchbayPalettes {

    /** The primary look: near-black base, charcoal cards, a soft off-white for text (never pure white). */
    val Dark = PatchbayColors(
        name = "dark",
        isDark = true,
        background = Color(0xFF0A0B0D),
        surface = Color(0xFF14161A),
        raised = Color(0xFF1E2126),
        outline = Color(0xFF727A87),
        divider = Color(0xFF24282E),
        text = Color(0xFFF2F4F7),
        textSecondary = Color(0xFFA7AEBA),
        textTertiary = Color(0xFF8D95A1),
        active = Color(0xFF2DD4BF),
        heat = Color(0xFFFB923C),
        cool = Color(0xFF60A5FA),
        attention = Color(0xFFFBBF24),
        alarm = Color(0xFFF87171),
        band = Color(0xFF262B31),
        onSignal = Color(0xFF06100E),
        inverse = Color(0xFFF2F4F7),
        onInverse = Color(0xFF0E1013),
    )

    /** A designed light theme, not an inversion: cool grey page, white cards, darker signals. */
    val Light = PatchbayColors(
        name = "light",
        isDark = false,
        background = Color(0xFFF2F3F5),
        surface = Color(0xFFFFFFFF),
        raised = Color(0xFFECEEF1),
        outline = Color(0xFF7B8491),
        divider = Color(0xFFE1E4E8),
        text = Color(0xFF101317),
        textSecondary = Color(0xFF48505B),
        textTertiary = Color(0xFF596069),
        active = Color(0xFF0A7468),
        heat = Color(0xFFB8430A),
        cool = Color(0xFF1D58C7),
        attention = Color(0xFF8A5600),
        alarm = Color(0xFFC02626),
        band = Color(0xFFE4E7EB),
        onSignal = Color(0xFFFFFFFF),
        inverse = Color(0xFF101317),
        onInverse = Color(0xFFF5F6F8),
    )

    /**
     * Night: very low luminance and warm-shifted (no blue-heavy light) for a dark bedroom.
     * Secondary and tertiary text collapse to nearly one level; hierarchy comes from size and weight.
     */
    val Night = PatchbayColors(
        name = "night",
        isDark = true,
        background = Color(0xFF0A0806),
        surface = Color(0xFF14110D),
        raised = Color(0xFF1C1712),
        outline = Color(0xFF7F6A55),
        divider = Color(0xFF201B15),
        text = Color(0xFFA88F76),
        textSecondary = Color(0xFF977F68),
        textTertiary = Color(0xFF937C66),
        active = Color(0xFF9A9A6A),
        heat = Color(0xFFC06A44),
        cool = Color(0xFF7D9297),
        attention = Color(0xFFB8913F),
        alarm = Color(0xFFE06A5C),
        band = Color(0xFF1A1511),
        onSignal = Color(0xFF0A0806),
        inverse = Color(0xFFA88F76),
        onInverse = Color(0xFF0A0806),
    )

    val all: List<PatchbayColors> = listOf(Dark, Light, Night)
}

/** WCAG 2.x relative luminance / contrast, used by tests and the design-system sheet. */
object Contrast {
    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
}
