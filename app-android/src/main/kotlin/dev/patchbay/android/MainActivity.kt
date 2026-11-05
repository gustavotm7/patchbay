package dev.patchbay.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.patchbay.simulators.DemoHome
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.PatchbayPalettes
import dev.patchbay.ui.designsystem.PatchbayTheme
import dev.patchbay.ui.designsystem.PatchbayTypefaces
import dev.patchbay.ui.screens.PhoneApp

/**
 * Phase 0 shell: renders the demo home. Bootstrap (paths → encrypted storage → keys → NetGuard →
 * logger → adapters) and app lock arrive in Phase 1.
 */
class MainActivity : ComponentActivity() {

    private val typefaces by lazy {
        PatchbayTypefaces(
            sans = FontFamily(
                Font(R.font.plex_sans_regular, FontWeight.Normal),
                Font(R.font.plex_sans_medium, FontWeight.Medium),
                Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
            ),
            mono = FontFamily(
                Font(R.font.plex_mono_regular, FontWeight.Normal),
                Font(R.font.plex_mono_medium, FontWeight.Medium),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The whole app shows home layout, presence and (later) pairing codes: keep it out of
        // screenshots and the recents thumbnail. A per-screen opt-out may come with app lock (Phase 1).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val home = DemoHome.home()
        val now = Fmt.timeOfDay(DemoHome.DEMO_NOW.value)
        setContent {
            val palette = if (isSystemInDarkTheme()) PatchbayPalettes.Dark else PatchbayPalettes.Light
            PatchbayTheme(palette, typefaces, FormFactor.PHONE) {
                PhoneApp(home, DemoHome.trends, now)
            }
        }
    }
}
