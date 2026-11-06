package dev.patchbay.ui.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The bundled typefaces, provided by each platform shell (see DesktopFonts / app-android). */
@Immutable
data class PatchbayTypefaces(val sans: FontFamily, val mono: FontFamily)

/**
 * Type scale. Every number on screen uses a `value*` style (tabular figures, so digits don't
 * jitter as they change). Section labels are small caps-style uppercase, like instrument legends.
 */
@Immutable
data class PatchbayType(
    /** Hero readouts: the current temperature, print progress. */
    val valueXL: TextStyle,
    /** Primary readouts inside panels. */
    val valueL: TextStyle,
    /** Inline values in lists and on the floor plan. */
    val valueM: TextStyle,
    val valueS: TextStyle,
    /** Unit suffix next to a value ("°C", "%", "K"). */
    val unit: TextStyle,
    /** Page titles on phone and desktop ("Home", a device's name on its page). */
    val largeTitle: TextStyle,
    /** Panel titles ("Thermostat", "H2D"). */
    val title: TextStyle,
    /** Section labels ("Setpoints", "AMS"): sentence case, quiet. */
    val legend: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
    /** IDs, codes, fingerprints, HMS codes. Slashed zero. */
    val mono: TextStyle,
) {
    companion object {
        private const val TNUM = "tnum, lnum"

        fun from(t: PatchbayTypefaces): PatchbayType = PatchbayType(
            valueXL = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Normal, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
            valueL = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Normal, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TNUM),
            valueM = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM),
            valueS = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM),
            unit = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM),
            largeTitle = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em),
            title = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
            legend = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
            body = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
            bodyStrong = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            caption = TextStyle(fontFamily = t.sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
            mono = TextStyle(fontFamily = t.mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "zero"),
        )
    }
}

/** 4 dp grid. */
object Space {
    val hair: Dp = 1.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp

    /** Minimum touch target on Android (and pointer target on desktop). */
    val touchTarget: Dp = 48.dp
    val pointerTarget: Dp = 32.dp
}

/**
 * Shapes (ADR 0009): generous, consistent radii. Cards and tiles are large rounded rectangles, controls are pills
 * or softly rounded fields, and small marks (swatches, PIN dots) use [chip].
 */
object Shapes {
    val panel: RoundedCornerShape = RoundedCornerShape(20.dp)
    val tile: RoundedCornerShape = RoundedCornerShape(22.dp)
    val control: RoundedCornerShape = RoundedCornerShape(14.dp)
    val chip: RoundedCornerShape = RoundedCornerShape(8.dp)
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50)
}

/**
 * Motion: fast (≤ 200 ms) and only for state change or spatial transition. With reduced motion
 * enabled, every duration becomes 0 (see [PatchbayTheme]).
 */
@Immutable
data class PatchbayMotion(val short: Int, val medium: Int, val easing: Easing) {
    companion object {
        val Standard = PatchbayMotion(short = 90, medium = 180, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
        val Reduced = PatchbayMotion(short = 0, medium = 0, easing = CubicBezierEasing(0f, 0f, 1f, 1f))
    }
}
