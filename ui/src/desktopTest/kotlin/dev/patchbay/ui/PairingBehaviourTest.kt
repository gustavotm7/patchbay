@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.patchbay.ui.screens.PairingDialogState
import dev.patchbay.ui.screens.PairingPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pairing is where a person hands over a code or trusts a device's key, so the panel is tested through the real screen: the fingerprint is shown
 * before trusting, a code is masked while typed, and cancelling answers "no" instead of hanging the adapter that is waiting.
 */
class PairingBehaviourTest {
    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun theFingerprintIsShownBeforeTrustingAndTrustAnswersYes(): Unit = runComposeUiTest {
        val state = PairingDialogState()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val answer = scope.async { state.confirmFingerprint("LG TV", "AB CD 12 34") }
            waitFor("the fingerprint step") { state.step != null }
            show { PairingPanel(state) }

            onNodeWithText("Check LG TV's key").assertExists()
            onNodeWithText("AB CD 12 34").assertExists()
            onNodeWithText("Trust").performClick()

            assertEquals(true, runBlocking { withTimeout(5_000) { answer.await() } })
        } finally { scope.cancel() }
    }

    @Test
    fun cancellingTheFingerprintAnswersNoAndClosesThePanel(): Unit = runComposeUiTest {
        val state = PairingDialogState()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val answer = scope.async { state.confirmFingerprint("LG TV", "AB CD 12 34") }
            waitFor("the fingerprint step") { state.step != null }
            show { PairingPanel(state) }

            onNodeWithText("Cancel").performClick()
            waitForIdle()

            assertEquals(false, runBlocking { withTimeout(5_000) { answer.await() } })
            onNodeWithText("Pairing").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test
    fun aCodeIsMaskedWhileTypedAndOnlyFitsTheLengthTheDeviceExpects(): Unit = runComposeUiTest {
        val state = PairingDialogState()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val answer = scope.async { state.askForCode("Access code (printer screen)", 8..8) }
            waitFor("the code step") { state.step != null }
            show { PairingPanel(state) }

            onNodeWithText("Access code (printer screen)").assertExists()
            onNodeWithText("OK").assertIsNotEnabled()

            onNode(hasSetTextAction()).performTextInput("1234 56789") // spaces dropped, extra digit cut off
            waitForIdle()

            onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            onNode(hasSetTextAction()).assert(
                SemanticsMatcher("the visible text has no digits in it") { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.any { it.isDigit() } != true
                },
            )
            onNodeWithText("OK").assertIsEnabled()
            onNodeWithText("OK").performClick()

            assertEquals("12345678", runBlocking { withTimeout(5_000) { answer.await() } })
        } finally { scope.cancel() }
    }

    @Test
    fun cancellingTheCodeAnswersNullSoTheAdapterStopsWaiting(): Unit = runComposeUiTest {
        val state = PairingDialogState()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val answer = scope.async { state.askForCode("Local key", 16..16) }
            waitFor("the code step") { state.step != null }
            show { PairingPanel(state) }

            onNodeWithText("Cancel").performClick()
            waitForIdle()

            assertNull(runBlocking { withTimeout(5_000) { answer.await() } })
            onNodeWithText("Pairing").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test
    fun aFailureIsShownAndClosesWithOneTap(): Unit = runComposeUiTest {
        val state = PairingDialogState()
        state.done("That code isn't right. Check the code on the TV and try again.")
        show { PairingPanel(state) }

        onNodeWithText("That code isn't right. Check the code on the TV and try again.").assertExists()
        onNodeWithText("Close").performClick()
        waitForIdle()

        onNodeWithText("Pairing").assertDoesNotExist()
    }
}
