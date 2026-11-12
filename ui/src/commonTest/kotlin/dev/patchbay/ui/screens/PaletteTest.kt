package dev.patchbay.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteTest {
    private fun item(t: String) = PaletteItem(t, "") {}

    @Test
    fun anEmptyQueryKeepsEverythingInOrder() {
        val items = listOf(item("Floor lamp"), item("Bedside"), item("Go to Activity"))
        assertEquals(items.map { it.title }, Palette.filter(items, "").map { it.title })
        assertEquals(items.map { it.title }, Palette.filter(items, "   ").map { it.title })
    }

    @Test
    fun startsWithBeatsContainsBeatsLettersInOrder() {
        val items = listOf(item("Shelf strip"), item("Floor lamp"), item("Living room lamp"), item("Lamp post"))
        assertEquals(listOf("Lamp post", "Floor lamp", "Living room lamp"), Palette.filter(items, "lamp").map { it.title })
        // letters in order (a typo-friendly match) come after real substrings
        assertEquals(listOf("Floor lamp", "Shelf strip"), Palette.filter(items, "fl").map { it.title }.take(2))
        assertTrue(Palette.filter(items, "flm").map { it.title }.contains("Floor lamp"))
    }

    @Test
    fun matchingIsCaseInsensitiveAndNonMatchesAreDropped() {
        val items = listOf(item("Workshop H2D"), item("Bedside"))
        assertEquals(listOf("Workshop H2D"), Palette.filter(items, "H2d").map { it.title })
        assertNull(Palette.score("xyz", "Bedside"))
        assertEquals(emptyList(), Palette.filter(items, "zzz"))
    }

    @Test
    fun resultsAreCappedAndStable() {
        val items = (1..20).map { item("Lamp $it") }
        val r = Palette.filter(items, "lamp")
        assertEquals(8, r.size)
        assertEquals((1..8).map { "Lamp $it" }, r.map { it.title }) // ties keep the original order
    }
}
