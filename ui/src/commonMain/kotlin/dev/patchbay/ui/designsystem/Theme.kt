package dev.patchbay.ui.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Form factor drives layout, not theme: phone = touch-first single pane, desktop = multi-pane, keyboard-first. */
enum class FormFactor { PHONE, DESKTOP }

private val LocalColors = staticCompositionLocalOf { PatchbayPalettes.Dark }
private val LocalType = staticCompositionLocalOf<PatchbayType> { error("PatchbayTheme not set") }
private val LocalMotion = staticCompositionLocalOf { PatchbayMotion.Standard }
private val LocalFormFactor = staticCompositionLocalOf { FormFactor.PHONE }

object Patchbay {
    val colors: PatchbayColors @Composable get() = LocalColors.current
    val type: PatchbayType @Composable get() = LocalType.current
    val motion: PatchbayMotion @Composable get() = LocalMotion.current
    val formFactor: FormFactor @Composable get() = LocalFormFactor.current
}

@Composable
fun PatchbayTheme(
    colors: PatchbayColors,
    typefaces: PatchbayTypefaces,
    formFactor: FormFactor,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides PatchbayType.from(typefaces),
        LocalMotion provides if (reducedMotion) PatchbayMotion.Reduced else PatchbayMotion.Standard,
        LocalFormFactor provides formFactor,
        LocalIndication provides FocusAndPressIndication(colors),
        LocalTextSelectionColors provides TextSelectionColors(handleColor = colors.active, backgroundColor = colors.band),
        content = content,
    )
}
