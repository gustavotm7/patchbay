package dev.patchbay.windows

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.patchbay.simulators.DemoHome
import dev.patchbay.ui.DesktopFonts
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.PatchbayPalettes
import dev.patchbay.ui.designsystem.PatchbayTheme
import dev.patchbay.ui.screens.DesktopApp

/**
 * Phase 0 shell: renders the demo home in the desktop multi-pane layout.
 * Native window decorations are kept for now (resize, snap layouts). The custom title bar is
 * Phase 1 and needs a decision on the JetBrains Runtime (docs/design/DESIGN_SPEC.md §7).
 */
fun main() = application {
    val home = DemoHome.home()
    val now = Fmt.timeOfDay(DemoHome.DEMO_NOW.value)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Patchbay — ${home.name}",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        val palette = if (isSystemInDarkTheme()) PatchbayPalettes.Dark else PatchbayPalettes.Light
        PatchbayTheme(palette, DesktopFonts.typefaces, FormFactor.DESKTOP) {
            DesktopApp(home, DemoHome.trends, now)
        }
    }
}
