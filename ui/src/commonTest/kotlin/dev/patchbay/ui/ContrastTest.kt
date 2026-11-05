package dev.patchbay.ui

import dev.patchbay.ui.designsystem.Contrast
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.PatchbayPalettes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WCAG 2.2 AA: text roles ≥ 4.5:1 and meaningful outlines ≥ 3:1 on every background layer. */
class ContrastTest {

    @Test
    fun textAndSignalRolesMeetAA() {
        for (p in PatchbayPalettes.all) {
            val grounds = mapOf("background" to p.background, "surface" to p.surface, "raised" to p.raised)
            val fgs = mapOf(
                "text" to p.text, "textSecondary" to p.textSecondary, "textTertiary" to p.textTertiary,
                "active" to p.active, "heat" to p.heat, "cool" to p.cool, "attention" to p.attention, "alarm" to p.alarm,
            )
            for ((gn, g) in grounds) for ((fn, f) in fgs) {
                val r = Contrast.ratio(f, g)
                assertTrue(r >= 4.5, "${p.name}: $fn on $gn is ${r} (< 4.5)")
            }
            for ((gn, g) in grounds) {
                val r = Contrast.ratio(p.outline, g)
                assertTrue(r >= 3.0, "${p.name}: outline on $gn is $r (< 3.0)")
            }
        }
    }

    @Test
    fun formatting() {
        assertEquals("20.6", Fmt.celsius(20.6))
        assertEquals("−2.5", Fmt.fixed(-2.5, 1))
        assertEquals("0.0", Fmt.fixed(-0.01, 1))
        assertEquals("1 h 36 min", Fmt.duration(96))
        assertEquals("19:30", Fmt.timeOfDay(1_768_505_400_000))
        assertEquals("32:14", Fmt.clock(1_934_000))
    }
}
