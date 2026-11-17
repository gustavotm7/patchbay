@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.simulators.DemoHome
import dev.patchbay.ui.screens.LiveDetail
import dev.patchbay.ui.screens.LiveDeviceList
import dev.patchbay.ui.screens.RemovePanel
import dev.patchbay.ui.screens.SceneStrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the home screen does when touched: lights and plugs switch on a tap and flip back if the device doesn't confirm, anything riskier opens
 * its page, a device that needs pairing asks for it, and removal takes two taps. Run against the real screens with a fake home.
 */
class TileBehaviourTest {
    private val home = DemoHome.home()
    private val lamp = DeviceId("govee-test-1")

    private fun ComposeUiTest.tile(description: String): SemanticsNodeInteraction = onNodeWithContentDescription(description)

    private fun SemanticsNodeInteraction.assertSwitch(on: Boolean): SemanticsNodeInteraction =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (on) "on" else "off"))

    @Test
    fun aTapFlipsALightAtOnceAndAsksTheDevice(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(on = false)))
        show { LiveDeviceList(live, null, {}) }

        tile("Desk lamp, Off").assertSwitch(on = false)
        tile("Desk lamp, Off").performClick()
        waitForIdle()

        tile("Desk lamp, Off").assertSwitch(on = true) // flipped on screen although the device has not reported yet
        assertEquals(listOf(lamp to Command.SetPower(true)), live.sent.toList())
    }

    @Test
    fun aRefusedCommandFlipsTheTileBackAtOnce(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(on = false)))
        live.sendResult = { PatchbayResult.Err(PatchbayError.Unreachable("no answer")) }
        show { LiveDeviceList(live, null, {}) }

        tile("Desk lamp, Off").performClick()
        waitForIdle()

        assertEquals(1, live.sent.size)
        tile("Desk lamp, Off").assertSwitch(on = false)
    }

    @Test
    fun aTileFlipsBackWhenTheDeviceNeverConfirms(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(on = false)))
        show { LiveDeviceList(live, null, {}) }

        tile("Desk lamp, Off").performClick()
        waitForIdle()
        tile("Desk lamp, Off").assertSwitch(on = true)

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(3_500)
        waitForIdle()

        tile("Desk lamp, Off").assertSwitch(on = false)
    }

    @Test
    fun aConfirmedTileStaysOnAfterTheWaitIsOver(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(on = false)))
        show { LiveDeviceList(live, null, {}) }

        tile("Desk lamp, Off").performClick()
        waitForIdle()
        live.deviceFlow.value = listOf(TestDevices.light(on = true)) // the lamp reports that it is on
        waitForIdle()
        tile("Desk lamp, On").assertSwitch(on = true)

        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(3_500)
        waitForIdle()

        tile("Desk lamp, On").assertSwitch(on = true)
    }

    @Test
    fun aTvIsNeverOneTapFromTurningOff(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.tv()))
        val opened = mutableListOf<DeviceId>()
        show { LiveDeviceList(live, null, { opened += it }) }

        tile("Living room TV, On").performClick()
        waitForIdle()

        assertTrue(live.sent.isEmpty(), "a tap on a TV must open its page, not send a command")
        assertEquals(listOf(DeviceId("lg-test-1")), opened)
    }

    @Test
    fun theDetailsButtonOpensTheLightInsteadOfSwitchingIt(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(on = false)))
        val opened = mutableListOf<DeviceId>()
        show { LiveDeviceList(live, null, { opened += it }) }

        onNodeWithContentDescription("Details for Desk lamp").performClick()
        waitForIdle()

        assertEquals(listOf(lamp), opened)
        assertTrue(live.sent.isEmpty())
    }

    @Test
    fun aDeviceThatNeedsPairingAsksToPairAndSendsNothing(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(needsPairing = true)))
        show { LiveDeviceList(live, null, {}) }

        tile("Desk lamp, Tap to pair").performClick()
        waitForIdle()

        assertEquals(listOf(lamp), live.pairRequests.toList())
        assertTrue(live.sent.isEmpty())
    }

    @Test
    fun aDeviceWithAChangedKeyNeverSwitchesAndIsFlaggedOnTheHomeScreen(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(trustChanged = true)))
        val opened = mutableListOf<DeviceId>()
        show { LiveDeviceList(live, null, { opened += it }) }

        onNodeWithText("Needs attention").assertExists()
        onNodeWithText("Different security key: Patchbay stopped talking to it").assertExists()
        tile("Desk lamp, Key changed").performClick()
        waitForIdle()

        assertTrue(live.sent.isEmpty(), "a light with a changed key must not be switched")
        assertEquals(listOf(lamp), opened)
    }

    @Test
    fun aSceneRunsOnATapAndSaysSo(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home)
        show { SceneStrip(live) }

        onNodeWithText("Movie night").performClick()
        waitForIdle()

        assertEquals(listOf("1"), live.scenesRun.toList())
        onNodeWithText("Movie night: done").assertExists()
    }

    @Test
    fun aSceneThatPartlyFailsShowsWhy(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home)
        live.runSceneResult = { PatchbayResult.Err(PatchbayError.Unreachable("1 of 2 devices didn't respond")) }
        show { SceneStrip(live) }

        onNodeWithText("Good night").performClick()
        waitForIdle()

        onNodeWithText("1 of 2 devices didn't respond").assertExists()
    }

    @Test
    fun allOffSaysHowManyWereSwitchedOff(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home)
        show { SceneStrip(live) }

        onNodeWithText("All off").performClick()
        waitForIdle()

        onNodeWithText("Switched off 2").assertExists()
    }

    @Test
    fun removingADeviceTakesASecondTapAndCancelBacksOut(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home)
        show { RemovePanel(live, DemoHome.Ids.FLOOR_LAMP) }

        onNodeWithText("Remove…").performClick()
        waitForIdle()
        assertTrue(live.forgotten.isEmpty(), "the first tap only asks")
        onNodeWithText("Confirm remove").assertExists()

        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onNodeWithText("Confirm remove").assertDoesNotExist()
        onNodeWithText("Remove…").assertExists()
        assertTrue(live.forgotten.isEmpty())

        onNodeWithText("Remove…").performClick()
        waitForIdle()
        onNodeWithText("Confirm remove").performClick()
        waitForIdle()
        assertEquals(listOf(DemoHome.Ids.FLOOR_LAMP), live.forgotten.toList())
    }

    @Test
    fun theLightPageSwitchesThroughItsPowerToggle(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(on = false)))
        show { LiveDetail(live, lamp) }

        onNodeWithText("Power").performClick()
        waitForIdle()

        assertEquals(listOf(lamp to Command.SetPower(true)), live.sent.toList())
    }

    @Test
    fun aDeviceWithAChangedKeyShowsTheAlertAndNoControls(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(trustChanged = true)))
        show { LiveDetail(live, lamp) }

        onNodeWithText("Security alert").assertExists()
        onNodeWithText("This device is presenting a different security key.").assertExists()
        onNodeWithText("Power").assertDoesNotExist()
    }

    @Test
    fun anOfflineDeviceSaysSoInsteadOfShowingDeadControls(): Unit = runComposeUiTest {
        val live = FakeLiveHome(home, listOf(TestDevices.light(reach = dev.patchbay.core.model.Reachability.OFFLINE)))
        show { LiveDetail(live, lamp) }

        onNodeWithText("No reply from this device.").assertExists()
        onNodeWithText("Power").assertDoesNotExist()
    }
}
