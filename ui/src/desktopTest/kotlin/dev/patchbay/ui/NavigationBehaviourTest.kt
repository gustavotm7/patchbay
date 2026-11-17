@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.patchbay.simulators.DemoHome
import dev.patchbay.ui.screens.AppSettings
import dev.patchbay.ui.screens.Appearance
import dev.patchbay.ui.screens.DesktopApp
import dev.patchbay.ui.screens.SettingsScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Moving around the app: the navigation rail, the keyboard shortcut for the command bar, and the one button the first hardware pass depends on. */
class NavigationBehaviourTest {
    private val home = DemoHome.home()
    private val settings = AppSettings(
        Appearance.SYSTEM, {}, {}, "windows-dpapi", "C:\\Users\\someone\\Desktop\\Patchbay\\PatchbayData", portable = true, version = "0.1.0",
    )

    private fun ComposeUiTest.nav(label: String): SemanticsNodeInteraction = onNode(hasText(label) and hasClickAction())

    @Test
    fun theNavRailSwitchesScreensAndMarksTheCurrentOne(): Unit = runComposeUiTest {
        show { DesktopApp(home, DemoHome.trends, "19:30", FakeLiveHome(home), settings) }

        nav("Devices").assertIsSelected()
        nav("Settings").assertIsNotSelected()
        onNodeWithText("Scan").assertExists()

        nav("Settings").performClick()
        waitForIdle()
        nav("Settings").assertIsSelected()
        nav("Devices").assertIsNotSelected()
        onNodeWithText("Appearance").assertExists()

        nav("Network").performClick()
        waitForIdle()
        onNodeWithText("Patchbay talks only to devices on your home network.").assertExists()

        nav("Activity").performClick()
        waitForIdle()
        onNodeWithText("What changed and when. Stays on this device.").assertExists()

        nav("Automations").performClick()
        waitForIdle()
        onNodeWithText("Scenes, schedules and away mode.").assertExists()

        nav("Devices").performClick()
        waitForIdle()
        onNodeWithText("Scan").assertExists()
    }

    @Test
    fun ctrlKOpensTheCommandBarAndEnterTakesYouWhereYouTyped(): Unit = runComposeUiTest {
        show { DesktopApp(home, DemoHome.trends, "19:30", FakeLiveHome(home), settings) }
        onNodeWithText("Search devices, scenes and screens").assertDoesNotExist()

        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        waitForIdle()
        onNodeWithText("Search devices, scenes and screens").assertExists()

        onNode(hasSetTextAction() and isFocused()).performTextInput("go to net")
        waitForIdle()
        onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        nav("Network").assertIsSelected()
        onNodeWithText("Search devices, scenes and screens").assertDoesNotExist()
    }

    @Test
    fun theDiagnosticsReportCanBeReadFirstAndTheHeaderHasNoPathOnThisComputer(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home)
        show { Column(Modifier.verticalScroll(rememberScrollState())) { SettingsScreen(settings, live = live) } }

        onNodeWithText("Copy diagnostics").performScrollTo().assertExists()
        onNodeWithText("Read it first").performScrollTo().performClick()
        // The report is built off the main thread, so wait for it instead of for idle.
        waitUntil(5_000) { onAllNodes(hasText("diagnostics for", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("diagnostics for", substring = true).assertExists()
        val header = assertNotNull(live.diagnosticsHeaders)
        assertEquals(listOf("Patchbay 0.1.0", "Key protection: windows-dpapi", "Portable build: true"), header)
        assertFalse(header.any { "Users" in it || "PatchbayData" in it }, "the folder on this computer must not be in what is shared")

        onNodeWithText("Hide").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("diagnostics for", substring = true).assertDoesNotExist()
    }
}
