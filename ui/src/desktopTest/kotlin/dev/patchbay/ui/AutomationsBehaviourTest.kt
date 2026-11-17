@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.patchbay.core.home.Automation
import dev.patchbay.core.home.DeviceEvent
import dev.patchbay.core.home.DeviceTrigger
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.simulators.DemoHome
import dev.patchbay.ui.screens.AutomationsPanel
import dev.patchbay.ui.screens.whenText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Making an automation that starts when a device does something: what the person picks is exactly what is asked of the home. */
class AutomationsBehaviourTest {
    private val home = DemoHome.home()

    // The form is taller than the test window, so it sits in a scroll container and every tap scrolls to its target first (a click outside the window goes nowhere).
    private fun ComposeUiTest.showPanel(fake: FakeLiveHome) = show { Column(Modifier.verticalScroll(rememberScrollState())) { AutomationsPanel(fake) } }
    private fun ComposeUiTest.tap(text: String) { onNodeWithText(text).performScrollTo().performClick() }
    private fun ComposeUiTest.typeName(name: String) { onNode(hasSetTextAction()).performScrollTo().performTextInput(name) }

    @Test
    fun aDeviceAutomationIsSavedWithTheDeviceAndEventThatWerePicked(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home)
        showPanel(fake)

        tap("New")
        tap("Device") // the time box goes away, leaving the name as the one text field
        typeName("Lamp off")
        tap("Good night")
        tap("Floor lamp")
        tap("Turns off")
        tap("Save")

        waitUntil(timeoutMillis = 5_000L) { fake.deviceAutomationRequests.isNotEmpty() }
        assertEquals(
            listOf(FakeLiveHome.DeviceAutomationRequest("Lamp off", "2", DemoHome.Ids.FLOOR_LAMP, DeviceEvent.TURNED_OFF)),
            fake.deviceAutomationRequests.toList(),
        )
    }

    @Test
    fun savingWithoutPickingADeviceAsksForOneAndSavesNothing(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home)
        showPanel(fake)

        tap("New")
        tap("Device")
        typeName("Nothing")
        tap("Save")

        onNodeWithText("Pick a device and what it does").assertExists()
        assertTrue(fake.deviceAutomationRequests.isEmpty())
    }

    @Test
    fun aRefusalFromTheHomeIsShownAndTheFormStaysOpen(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home).also { it.deviceAutomationResult = PatchbayResult.Err(PatchbayError.InvalidArgument("Floor lamp can't tell Patchbay when it turns off")) }
        showPanel(fake)

        tap("New")
        tap("Device")
        typeName("Refused")
        tap("Floor lamp")
        tap("Save")

        waitUntil(timeoutMillis = 5_000L) { fake.deviceAutomationRequests.isNotEmpty() }
        onNodeWithText("Floor lamp can't tell Patchbay when it turns off").assertExists()
        onNodeWithText("Save").assertExists()
    }

    @Test
    fun aDeviceAutomationRowSaysWhenItRunsAndAForgottenDeviceIsNamedAsRemoved(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home)
        showPanel(fake)
        onNodeWithText("When Floor lamp turns on · Good night").assertExists()

        val orphan = Automation("x", "Orphan", "1", "Movie night", 0, 0, (1..7).toSet(), true, trigger = DeviceTrigger(DeviceId("gone"), null, DeviceEvent.PRINT_FINISHED))
        assertEquals("When a removed device print finishes", whenText(orphan))
        assertEquals("07:30 · Weekdays", whenText(Automation("t", "Morning", "1", "Movie night", 7, 30, setOf(1, 2, 3, 4, 5), true)))
    }
}
