package dev.patchbay.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import dev.patchbay.ui.designsystem.PatchbayTypefaces

/**
 * Loads the bundled IBM Plex fonts from the classpath (the TTFs in ui/src/fonts/font).
 * No system font fallback for primary text, and never a network fetch.
 */
object DesktopFonts {
    private fun bytes(name: String): ByteArray {
        val stream = DesktopFonts::class.java.classLoader.getResourceAsStream("font/$name")
            ?: error("bundled font missing: font/$name")
        return stream.use { it.readBytes() }
    }

    val typefaces: PatchbayTypefaces by lazy {
        PatchbayTypefaces(
            sans = FontFamily(
                Font("plex_sans_regular", bytes("plex_sans_regular.ttf"), FontWeight.Normal),
                Font("plex_sans_medium", bytes("plex_sans_medium.ttf"), FontWeight.Medium),
                Font("plex_sans_semibold", bytes("plex_sans_semibold.ttf"), FontWeight.SemiBold),
            ),
            mono = FontFamily(
                Font("plex_mono_regular", bytes("plex_mono_regular.ttf"), FontWeight.Normal),
                Font("plex_mono_medium", bytes("plex_mono_medium.ttf"), FontWeight.Medium),
            ),
        )
    }
}
