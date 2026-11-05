package dev.patchbay.android

import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
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
 * Android shell. Services come from [PatchbayApplication] (Bootstrap at process start). Shows live LAN
 * devices plus the demo home. App lock arrives later in Phase 1.
 */
class MainActivity : ComponentActivity() {

    private var multicastLock: WifiManager.MulticastLock? = null

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

        requestLocalNetworkIfNeeded()
        val live = (application as PatchbayApplication).services.home
        val home = DemoHome.home()
        val now = Fmt.timeOfDay(DemoHome.DEMO_NOW.value)
        setContent {
            val palette = if (isSystemInDarkTheme()) PatchbayPalettes.Dark else PatchbayPalettes.Light
            PatchbayTheme(palette, typefaces, FormFactor.PHONE) {
                PhoneApp(home, DemoHome.trends, now, live)
            }
        }
    }

    /** Android 17 (API 37) local network protection: runtime permission in the Nearby devices group. */
    private fun requestLocalNetworkIfNeeded() {
        if (Build.VERSION.SDK_INT < 37) return
        if (checkSelfPermission(PERMISSION_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(PERMISSION_LOCAL_NETWORK), 1)
    }

    // Multicast (discovery) only while the app is visible. Holding the lock costs battery.
    override fun onStart() {
        super.onStart()
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifi.createMulticastLock("patchbay-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStop() {
        multicastLock?.release()
        multicastLock = null
        super.onStop()
    }

    private companion object {
        const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
