package dev.patchbay.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.patchbay.simulators.DemoHome
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.PatchbayColors
import dev.patchbay.ui.designsystem.PatchbayPalettes
import dev.patchbay.ui.designsystem.PatchbayTheme
import dev.patchbay.ui.screens.DesignSheet
import dev.patchbay.ui.screens.DesktopShell
import dev.patchbay.ui.screens.DeviceScreenPhone
import dev.patchbay.ui.screens.Focus
import dev.patchbay.ui.screens.HomeScreenPhone
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Renders the key screens in every theme at phone and desktop sizes, from simulator data.
 * Output: build/screenshots-actual/<name>.png; when goldens exist in docs/design/screens/ they
 * are compared (tolerant of tiny antialiasing differences). `./gradlew :ui:recordScreens` rewrites them.
 */
class ScreenshotTest {

    private val actualDir = File(System.getProperty("patchbay.screens.actual") ?: "build/screenshots-actual")
    private val goldenDir = File(System.getProperty("patchbay.screens.golden") ?: "../docs/design/screens")
    private val record = System.getProperty("patchbay.screens.record") == "true"

    private val home = DemoHome.home()
    private val trends = DemoHome.trends
    private val now = Fmt.timeOfDay(DemoHome.DEMO_NOW.value)

    private data class Size(val w: Int, val h: Int, val density: Float)

    // Phone: 412 × 915 dp (typical 6.x" Android) at 2×. Desktop: 1440 × 900 dp at 1×.
    private val phone = Size(412, 915, 2f)
    private val phoneTall = Size(412, 1480, 2f)
    private val desktop = Size(1440, 900, 1f)
    private val sheet = Size(1440, 1040, 1f)

    private val failures = mutableListOf<String>()

    @Test
    fun renderAll() {
        for (palette in PatchbayPalettes.all) {
            val t = palette.name
            shot("home_phone_$t", phoneTall, palette, FormFactor.PHONE) { HomeScreenPhone(home, now) }
            shot("thermostat_phone_$t", phoneTall, palette, FormFactor.PHONE) { DeviceScreenPhone(home, trends, DemoHome.Ids.THERMOSTAT) }
            shot("printer_phone_$t", phoneTall, palette, FormFactor.PHONE) { DeviceScreenPhone(home, trends, DemoHome.Ids.PRINTER) }
            shot("home_desktop_$t", desktop, palette, FormFactor.DESKTOP) {
                DesktopShell(home, trends, Focus.RoomFocus(DemoHome.Ids.WORKSHOP), now)
            }
            shot("thermostat_desktop_$t", desktop, palette, FormFactor.DESKTOP) {
                DesktopShell(home, trends, Focus.DeviceFocus(DemoHome.Ids.THERMOSTAT), now)
            }
            shot("printer_desktop_$t", desktop, palette, FormFactor.DESKTOP) {
                DesktopShell(home, trends, Focus.DeviceFocus(DemoHome.Ids.PRINTER), now)
            }
            shot("designsystem_$t", sheet, palette, FormFactor.DESKTOP) { DesignSheet() }
        }
        // Above-the-fold phone render (what you see without scrolling).
        shot("home_phone_dark_fold", phone, PatchbayPalettes.Dark, FormFactor.PHONE) { HomeScreenPhone(home, now) }
        if (failures.isNotEmpty()) fail("Screenshots differ from goldens:\n" + failures.joinToString("\n"))
    }

    private fun shot(name: String, size: Size, palette: PatchbayColors, ff: FormFactor, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = (size.w * size.density).toInt(),
            height = (size.h * size.density).toInt(),
            density = Density(size.density),
        ) {
            PatchbayTheme(palette, DesktopFonts.typefaces, ff) { content() }
        }
        val png = try {
            scene.render().encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed for $name")
        } finally {
            scene.close()
        }

        actualDir.mkdirs()
        File(actualDir, "$name.png").writeBytes(png)
        val golden = File(goldenDir, "$name.png")
        if (record) {
            goldenDir.mkdirs()
            golden.writeBytes(png)
        } else if (golden.exists()) {
            val diff = differingFraction(golden.readBytes(), png)
            if (diff > 0.002) failures += "$name: ${"%.3f".format(diff * 100)} % of pixels differ"
        }
    }

    /** Fraction of pixels whose channels differ by more than 24/255 (ignores antialiasing noise). */
    private fun differingFraction(a: ByteArray, b: ByteArray): Double {
        val ia = Image.makeFromEncoded(a)
        val ib = Image.makeFromEncoded(b)
        if (ia.width != ib.width || ia.height != ib.height) return 1.0
        val pa = org.jetbrains.skia.Bitmap.makeFromImage(ia)
        val pb = org.jetbrains.skia.Bitmap.makeFromImage(ib)
        var bad = 0L
        for (y in 0 until ia.height) for (x in 0 until ia.width) {
            val ca = pa.getColor(x, y)
            val cb = pb.getColor(x, y)
            if (ca != cb) {
                val d = maxOf(
                    abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)),
                    abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)),
                    abs((ca and 0xFF) - (cb and 0xFF)),
                )
                if (d > 24) bad++
            }
        }
        return bad.toDouble() / (ia.width.toLong() * ia.height)
    }
}
