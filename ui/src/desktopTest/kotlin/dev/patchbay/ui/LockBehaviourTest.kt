@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.ComposeUiTest
import dev.patchbay.core.lock.AppLock
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.screens.LockController
import dev.patchbay.ui.screens.LockGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app lock as a person meets it on Windows: a PIN pad that hides what is typed and keeps the app's content away until the PIN is right. */
class LockBehaviourTest {
    private class FakeLock(pinSet: Boolean, private val correct: String = "2468") : LockController {
        val flow = MutableStateFlow(AppLock.State.LOCKED)
        override val state: StateFlow<AppLock.State> = flow
        override val usesSystemAuth: Boolean = false
        override var pinIsSet: Boolean = pinSet
        val pinsSet = mutableListOf<String>()
        @Volatile var wait: Long? = null

        override fun setPin(pin: String) { pinsSet += pin; pinIsSet = true; flow.value = AppLock.State.UNLOCKED }

        override fun tryPin(pin: String): LockController.PinResult {
            wait?.let { return LockController.PinResult.Wait(it) }
            return if (pin == correct) { flow.value = AppLock.State.UNLOCKED; LockController.PinResult.Ok } else LockController.PinResult.Wrong
        }
    }

    private fun ComposeUiTest.type(digits: String) {
        for (d in digits) onNodeWithText(d.toString()).performClick()
    }

    private fun ComposeUiTest.gate(lock: FakeLock) = show { LockGate(lock) { PText("Secret content", Patchbay.type.title) } }

    @Test
    fun contentStaysHiddenUntilUnlockedAndAppearsWhenItIs(): Unit = runComposeUiTest {
        val lock = FakeLock(pinSet = true)
        gate(lock)
        onNodeWithText("Locked").assertExists()
        onNodeWithText("Secret content").assertDoesNotExist()

        lock.flow.value = AppLock.State.UNLOCKED
        waitForIdle()

        onNodeWithText("Secret content").assertExists()
    }

    @Test
    fun theRightPinUnlocksAndAWrongOneDoesNot(): Unit = runComposeUiTest {
        val lock = FakeLock(pinSet = true)
        gate(lock)

        type("1111")
        onNodeWithText("OK").performClick()
        waitForIdle()
        onNodeWithText("Wrong PIN").assertExists()
        onNodeWithText("Secret content").assertDoesNotExist()

        type("2468")
        onNodeWithText("OK").performClick()
        waitForIdle()
        onNodeWithText("Secret content").assertExists()
    }

    @Test
    fun theDigitsTypedAreNeverShownOnlyHowManyThereAre(): Unit = runComposeUiTest {
        gate(FakeLock(pinSet = true))

        type("135")

        onNodeWithContentDescription("3 digits entered").assertExists()
        onAllNodesWithText("135").assertCountEquals(0)
    }

    @Test
    fun okWaitsForFourDigitsAndBackspaceTakesOneAway(): Unit = runComposeUiTest {
        gate(FakeLock(pinSet = true))

        onNodeWithText("OK").assertIsNotEnabled()
        type("123")
        onNodeWithText("OK").assertIsNotEnabled()
        type("4")
        onNodeWithText("OK").assertIsEnabled()
        onNodeWithText("⌫").performClick()
        onNodeWithContentDescription("3 digits entered").assertExists()
        onNodeWithText("OK").assertIsNotEnabled()
    }

    @Test
    fun tooManyWrongTriesShowHowLongToWait(): Unit = runComposeUiTest {
        val lock = FakeLock(pinSet = true)
        lock.wait = 2_500
        gate(lock)

        type("9999")
        onNodeWithText("OK").performClick()
        waitForIdle()

        onNodeWithText("Too many attempts — wait 3 s").assertExists()
        onNodeWithText("Secret content").assertDoesNotExist()
    }

    @Test
    fun aNewPinMustBeTypedTwiceTheSameBeforeItIsSet(): Unit = runComposeUiTest {
        val lock = FakeLock(pinSet = false)
        gate(lock)
        onNodeWithText("Create a PIN (4–12 digits)").assertExists()

        type("1234")
        onNodeWithText("OK").performClick()
        waitForIdle()
        onNodeWithText("Enter it again to confirm").assertExists()
        assertTrue(lock.pinsSet.isEmpty(), "the PIN is not set after one entry")

        type("1234")
        onNodeWithText("OK").performClick()
        waitForIdle()

        assertEquals(listOf("1234"), lock.pinsSet)
        onNodeWithText("Secret content").assertExists()
    }

    @Test
    fun twoDifferentEntriesStartAgainAndSetNothing(): Unit = runComposeUiTest {
        val lock = FakeLock(pinSet = false)
        gate(lock)

        type("1234")
        onNodeWithText("OK").performClick()
        waitForIdle()
        type("1235")
        onNodeWithText("OK").performClick()
        waitForIdle()

        onNodeWithText("PINs didn't match — start again").assertExists()
        onNodeWithText("Create a PIN (4–12 digits)").assertExists()
        assertTrue(lock.pinsSet.isEmpty())
        onNodeWithText("Secret content").assertDoesNotExist()
    }
}
