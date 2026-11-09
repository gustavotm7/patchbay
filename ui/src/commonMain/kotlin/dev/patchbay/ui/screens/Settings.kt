package dev.patchbay.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.PatchbayColors
import dev.patchbay.ui.designsystem.PatchbayPalettes
import dev.patchbay.ui.designsystem.Segmented
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.launch

/** Which palette to use. System follows the OS light/dark setting; Night is the warm, very dim bedroom theme. */
enum class Appearance(val label: String) {
    SYSTEM("System"), DARK("Dark"), LIGHT("Light"), NIGHT("Night");

    fun palette(systemDark: Boolean): PatchbayColors = when (this) {
        SYSTEM -> if (systemDark) PatchbayPalettes.Dark else PatchbayPalettes.Light
        DARK -> PatchbayPalettes.Dark
        LIGHT -> PatchbayPalettes.Light
        NIGHT -> PatchbayPalettes.Night
    }

    companion object {
        fun fromName(name: String?): Appearance = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** What the Settings screen shows and can change. Built by each app shell. */
class AppSettings(
    val appearance: Appearance,
    val onAppearance: (Appearance) -> Unit,
    /** Null where the app can't lock itself on demand. */
    val onLockNow: (() -> Unit)?,
    /** How the data key is protected, e.g. "windows-dpapi" or "android-keystore". */
    val keyProtection: String,
    /** Where data lives, in words a person can act on. */
    val dataLocation: String,
    /** Portable Windows build: the folder holds everything. */
    val portable: Boolean,
    val version: String,
    /** Windows only: keep running in the system tray when the window is closed. Null where there is no tray. */
    val trayMode: Boolean? = null,
    val onTrayMode: ((Boolean) -> Unit)? = null,
    /** Words for the background toggle; Windows says "tray", Android says "notification". */
    val backgroundLabel: String = "Keep running in the tray when the window is closed",
    val backgroundNote: String = "Patchbay keeps watching your devices and running automations. It locks when the window is hidden. Quit from the tray icon.",
)

@Composable
fun SettingsScreen(s: AppSettings, modifier: Modifier = Modifier, live: dev.patchbay.core.home.LiveHome? = null, onOpenDemo: (() -> Unit)? = null) {
    val c = Patchbay.colors
    Column(modifier) {
        PText("Settings", Patchbay.type.largeTitle)
        VSpace(Space.l)
        Panel(Modifier.fillMaxWidth()) {
            Legend("Appearance")
            VSpace(Space.s)
            Segmented(Appearance.entries, s.appearance, { it.label }, s.onAppearance, Modifier.fillMaxWidth())
            VSpace(Space.xs)
            PText("System follows your device. Night is a warm, very dim theme for a dark bedroom.", Patchbay.type.caption, c.textSecondary)
        }
        if (live != null) HomeLocationPanel(live)
        if (s.onLockNow != null) {
            VSpace(Space.m)
            Panel(Modifier.fillMaxWidth()) {
                Legend("App lock")
                VSpace(Space.xs)
                PText("Patchbay locks itself after a minute away.", Patchbay.type.body, c.textSecondary)
                VSpace(Space.s)
                PButton("Lock now", { s.onLockNow.invoke() })
            }
        }
        if (s.trayMode != null && s.onTrayMode != null) {
            VSpace(Space.m)
            Panel(Modifier.fillMaxWidth()) {
                Legend("Background")
                VSpace(Space.xs)
                dev.patchbay.ui.designsystem.PToggle(s.backgroundLabel, s.trayMode, s.onTrayMode)
                VSpace(Space.xs)
                PText(s.backgroundNote, Patchbay.type.caption, c.textSecondary)
            }
        }
        if (onOpenDemo != null) {
            VSpace(Space.m)
            Panel(Modifier.fillMaxWidth()) {
                Legend("Demo home")
                VSpace(Space.xs)
                PText("A made-up home for trying the layouts. It is not your devices.", Patchbay.type.body, c.textSecondary)
                VSpace(Space.s)
                PButton("Open demo home", onOpenDemo)
            }
        }
        VSpace(Space.m)
        Panel(Modifier.fillMaxWidth()) {
            Legend("Your data")
            VSpace(Space.xs)
            PText(s.dataLocation, Patchbay.type.mono, c.text)
            VSpace(Space.xs)
            PText(
                if (s.portable) "Portable: everything is in this folder, so deleting the Patchbay folder removes all of it."
                else "Uninstalling or deleting the app does not remove this folder. Delete it too if you want everything gone.",
                Patchbay.type.caption, c.textSecondary,
            )
            VSpace(Space.s)
            PText("Encrypted at rest. Key protection: ${s.keyProtection}.", Patchbay.type.caption, c.textSecondary)
        }
        VSpace(Space.m)
        Panel(Modifier.fillMaxWidth()) {
            Legend("About")
            VSpace(Space.xs)
            PText("Patchbay ${s.version}", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            PText(
                "Local only: no account, no analytics, no updater, and no connection to anything outside your home network. Devices are reached directly on your Wi-Fi.",
                Patchbay.type.caption, c.textSecondary,
            )
        }
    }
}

/** The owner types an approximate latitude and longitude, used only to work out sunrise and sunset. Never read from the phone. */
@Composable
private fun HomeLocationPanel(live: dev.patchbay.core.home.LiveHome) {
    val c = Patchbay.colors
    val loc by live.location.collectAsState()
    val scope = rememberCoroutineScope()
    var lat by remember(loc) { mutableStateOf(loc?.latitude?.toString() ?: "") }
    var lon by remember(loc) { mutableStateOf(loc?.longitude?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    VSpace(Space.m)
    Panel(Modifier.fillMaxWidth()) {
        Legend("Home location")
        VSpace(Space.xs)
        PText("For sunrise and sunset automations. You type it; it stays on this device and is never read from your phone. A town-level value is enough.", Patchbay.type.caption, c.textSecondary)
        VSpace(Space.s)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for ((label, value, set) in listOf(Triple("Latitude", lat, { v: String -> lat = v }), Triple("Longitude", lon, { v: String -> lon = v }))) {
                Column(Modifier.weight(1f)) {
                    PText(label, Patchbay.type.bodyStrong)
                    VSpace(Space.xs)
                    BasicTextField(
                        value = value, onValueChange = { v -> set(v.filter { it.isDigit() || it == '-' || it == '.' }.take(10)) }, singleLine = true,
                        textStyle = Patchbay.type.valueM.copy(color = c.text),
                        modifier = Modifier.fillMaxWidth().androidx_border(c),
                    )
                }
            }
        }
        error?.let {
            VSpace(Space.xs)
            PText(it, Patchbay.type.caption, c.alarm)
        }
        VSpace(Space.s)
        PButton("Save location", {
            val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
            if (la == null || lo == null) error = "Enter numbers, for example 51.5 and -0.12"
            else scope.launch { error = (live.setLocation(la, lo) as? dev.patchbay.core.result.PatchbayResult.Err)?.error?.message }
        })
    }
}

private fun Modifier.androidx_border(c: PatchbayColors): Modifier =
    this.border(Space.hair, c.outline, dev.patchbay.ui.designsystem.Shapes.control).padding(Space.m)
