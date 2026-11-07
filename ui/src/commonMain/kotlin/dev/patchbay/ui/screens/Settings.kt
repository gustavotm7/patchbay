package dev.patchbay.ui.screens

import androidx.compose.foundation.layout.Column
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
)

@Composable
fun SettingsScreen(s: AppSettings, modifier: Modifier = Modifier) {
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
                dev.patchbay.ui.designsystem.PToggle("Keep running in the tray when the window is closed", s.trayMode, s.onTrayMode)
                VSpace(Space.xs)
                PText(
                    "Patchbay keeps watching your devices and running automations. It locks when the window is hidden. Quit from the tray icon.",
                    Patchbay.type.caption, c.textSecondary,
                )
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
