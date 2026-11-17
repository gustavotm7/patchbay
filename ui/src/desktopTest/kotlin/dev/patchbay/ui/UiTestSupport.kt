@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.PatchbayPalettes
import dev.patchbay.ui.designsystem.PatchbayTheme

/** Puts [content] in the real theme (bundled fonts, dark palette, desktop form factor) on the palette's background, as the app does. */
internal fun ComposeUiTest.show(content: @Composable () -> Unit) = setContent {
    val palette = PatchbayPalettes.Dark
    PatchbayTheme(palette, DesktopFonts.typefaces, FormFactor.DESKTOP) {
        Box(Modifier.fillMaxSize().background(palette.background)) { content() }
    }
}

/** Devices with known names and states, so a test can say exactly what it expects on screen. */
internal object TestDevices {
    fun light(
        id: String = "govee-test-1", name: String = "Desk lamp", on: Boolean = false,
        reach: Reachability = Reachability.ONLINE, trustChanged: Boolean = false, needsPairing: Boolean = false,
    ): LiveDevice = LiveDevice(
        Device(DeviceId(id), name, DeviceKind.LIGHT, "Govee", "H6076", null, null, ControlPath.LOCAL, listOf(Capability.Power), AdapterId("govee-lan")),
        if (needsPairing) null else DeviceSnapshot(
            DeviceId(id), reach, if (reach == Reachability.ONLINE) listOf(CapabilityState.PowerState(on)) else emptyList(), EpochMillis(1_000),
        ),
        address = "192.168.1.20", needsPairing = needsPairing, trustChanged = trustChanged,
    )

    fun tv(id: String = "lg-test-1", name: String = "Living room TV", on: Boolean = true): LiveDevice = LiveDevice(
        Device(DeviceId(id), name, DeviceKind.TV, "LG", "OLED", null, null, ControlPath.LOCAL, listOf(Capability.Power), AdapterId("lg-webos")),
        DeviceSnapshot(DeviceId(id), Reachability.ONLINE, listOf(CapabilityState.PowerState(on)), EpochMillis(1_000)),
        address = "192.168.1.21",
    )
}
