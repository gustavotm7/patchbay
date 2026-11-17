@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun aDeviceAutomationIsSavedWithTheDeviceAndEventThatWerePicked(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home)
        show { AutomationsPanel(fake) }

        onNodeWithText("New").performClick()
        onNodeWithText("Device").performClick() // the time box goes away, leaving the name as the one text field
        onNode(hasSetTextAction()).performTextInput("Lamp off")
        onNodeWithText("Good night").performClick()
        onNodeWithText("Floor lamp").performClick()
        onNodeWithText("Turns off").performClick()
        onNodeWithText("Save").performClick()

        waitUntil(timeoutMillis = 5_000L) { fake.deviceAutomationRequests.isNotEmpty() }
        assertEquals(
            listOf(FakeLiveHome.DeviceAutomationRequest("Lamp off", "2", DemoHome.Ids.FLOOR_LAMP, DeviceEvent.TURNED_OFF)),
            fake.deviceAutomationRequests.toList(),
        )
    }

    @Test
    fun savingWithoutPickingADeviceAsksForOneAndSavesNothing(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home)
        show { AutomationsPanel(fake) }

        onNodeWithText("New").performClick()
        onNodeWithText("Device").performClick()
        onNode(hasSetTextAction()).performTextInput("Nothing")
        onNodeWithText("Save").performClick()

        onNodeWithText("Pick a device and what it does").assertExists()
        assertTrue(fake.deviceAutomationRequests.isEmpty())
    }

    @Test
    fun aRefusalFromTheHomeIsShownAndTheFormStaysOpen(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home).also { it.deviceAutomationResult = PatchbayResult.Err(PatchbayError.InvalidArgument("Floor lamp can't tell Patchbay when it turns off")) }
        show { AutomationsPanel(fake) }

        onNodeWithText("New").performClick()
        onNodeWithText("Device").performClick()
        onNode(hasSetTextAction()).performTextInput("Refused")
        onNodeWithText("Floor lamp").performClick()
        onNodeWithText("Save").performClick()

        waitUntil(timeoutMillis = 5_000L) { fake.deviceAutomationRequests.isNotEmpty() }
        onNodeWithText("Floor lamp can't tell Patchbay when it turns off").assertExists()
        onNodeWithText("Save").assertExists()
    }

    @Test
    fun aDeviceAutomationRowSaysWhenItRunsAndAForgottenDeviceIsNamedAsRemoved(): Unit = runComposeUiTest {
        val fake = FakeLiveHome(home)
        show { AutomationsPanel(fake) }
        onNodeWithText("When Floor lamp turns on · Good night").assertExists()

        val orphan = Automation("x", "Orphan", "1", "Movie night", 0, 0, (1..7).toSet(), true, trigger = DeviceTrigger(DeviceId("gone"), null, DeviceEvent.PRINT_FINISHED))
        assertEquals("When a removed device print finishes", whenText(orphan))
        assertEquals("07:30 · Weekdays", whenText(Automation("t", "Morning", "1", "Movie night", 7, 30, setOf(1, 2, 3, 4, 5), true)))
    }
}
