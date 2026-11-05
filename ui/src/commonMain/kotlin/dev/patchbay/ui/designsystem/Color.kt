package dev.patchbay.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Colour roles for the "Control Room" design (docs/design/DESIGN_SPEC.md §2).
 *
 * Rule: the baseline is neutral grey. Saturated colour appears only when it means something —
 * active output, heat/cool, attention, alarm — or when showing a device's real emitted colour.
 * Colour is never the only signal; every use pairs it with shape, position or text.
 *
 * All text roles meet WCAG 2.2 AA (≥ 4.5:1) on background, surface and raised; [outline] meets
 * the 3:1 non-text minimum. Enforced by ContrastTest.
 */
@Immutable
data class PatchbayColors(
    val name: String,
    val isDark: Boolean,
    /** Window/page background. */
    val background: Color,
    /** Panels and cards. */
    val surface: Color,
    /** Controls and inset wells inside a panel. */
    val raised: Color,
    /** Hairlines that carry meaning: control boundaries, room outlines (≥ 3:1). */
    val outline: Color,
    /** Decorative separators only (no contrast requirement; never the only boundary). */
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
    /** Text/glyph colour on top of [active], [attention] or [alarm] fills. */
    val onSignal: Color,
)

object PatchbayPalettes {

    val Dark = PatchbayColors(
        name = "dark",
        isDark = true,
        background = Color(0xFF111315),
        surface = Color(0xFF181B1E),
        raised = Color(0xFF202428),
        outline = Color(0xFF6E757D),
        divider = Color(0xFF2A2F34),
        text = Color(0xFFE7E9EB),
        textSecondary = Color(0xFFA9AFB6),
        textTertiary = Color(0xFF8A9199),
        active = Color(0xFF4FC3D9),
        heat = Color(0xFFF08A4B),
        cool = Color(0xFF5AA8F0),
        attention = Color(0xFFF2B13C),
        alarm = Color(0xFFFF6B66),
        band = Color(0xFF262B30),
        onSignal = Color(0xFF0B0D0F),
    )

    /** A designed light theme, not an inversion: ISA-101-style mid-light grey, darker signals. */
    val Light = PatchbayColors(
        name = "light",
        isDark = false,
        background = Color(0xFFE3E5E8),
        surface = Color(0xFFF2F3F5),
        raised = Color(0xFFFFFFFF),
        outline = Color(0xFF7A8088),
        divider = Color(0xFFD0D3D7),
        text = Color(0xFF16191C),
        textSecondary = Color(0xFF454B53),
        textTertiary = Color(0xFF585E66),
        active = Color(0xFF00697C),
        heat = Color(0xFFA8481A),
        cool = Color(0xFF1C5DA8),
        attention = Color(0xFF7F5300),
        alarm = Color(0xFFB71C1C),
        band = Color(0xFFDDE0E4),
        onSignal = Color(0xFFFFFFFF),
    )

    /**
     * Night: very low luminance and warm-shifted (no blue-heavy light) for a dark bedroom.
     * Secondary and tertiary text collapse to nearly one level; hierarchy comes from size and weight.
     */
    val Night = PatchbayColors(
        name = "night",
        isDark = true,
        background = Color(0xFF0A0806),
        surface = Color(0xFF100D0A),
        raised = Color(0xFF17130F),
        outline = Color(0xFF725F4C),
        divider = Color(0xFF1F1A15),
        text = Color(0xFFA88F76),
        textSecondary = Color(0xFF977F68),
        textTertiary = Color(0xFF937C66),
        active = Color(0xFF9A9A6A),
        heat = Color(0xFFC06A44),
        cool = Color(0xFF7D9297),
        attention = Color(0xFFB8913F),
        alarm = Color(0xFFD0574A),
        band = Color(0xFF1A1511),
        onSignal = Color(0xFF0A0806),
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
