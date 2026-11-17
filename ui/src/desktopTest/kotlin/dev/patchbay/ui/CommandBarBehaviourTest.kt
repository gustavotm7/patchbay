@file:OptIn(ExperimentalTestApi::class)

package dev.patchbay.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import dev.patchbay.ui.screens.CommandBar
import dev.patchbay.ui.screens.PaletteItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The command bar, driven the way a person drives it: typing, arrow keys, Enter, Escape and the mouse. */
class CommandBarBehaviourTest {
    private fun items(ran: MutableList<String>) = listOf(
        PaletteItem("Floor lamp", "open") { ran += "Floor lamp" },
        PaletteItem("Turn off Floor lamp", "off") { ran += "Turn off Floor lamp" },
        PaletteItem("Go to Settings", "screen") { ran += "Go to Settings" },
        PaletteItem("Go to Network", "screen") { ran += "Go to Network" },
    )

    @Test
    fun typingNarrowsTheListAndNothingRunsUntilEnter(): Unit = runComposeUiTest {
        val ran = mutableListOf<String>()
        var closed = 0
        show { CommandBar(items(ran)) { closed++ } }
        onNodeWithText("Go to Settings").assertExists()
        onNodeWithText("Go to Network").assertExists()

        onNode(hasSetTextAction()).performTextInput("net")
        waitForIdle()

        onNodeWithText("Go to Network").assertExists()
        onNodeWithText("Go to Settings").assertDoesNotExist()
        onNodeWithText("Floor lamp").assertDoesNotExist()
        assertTrue(ran.isEmpty(), "typing must not run anything")
        assertEquals(0, closed)
    }

    @Test
    fun downThenEnterRunsTheSecondResultAfterClosing(): Unit = runComposeUiTest {
        val ran = mutableListOf<String>()
        val order = mutableListOf<String>()
        show { CommandBar(items(ran)) { order += "closed" } }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Turn off Floor lamp"), ran)
        assertEquals(listOf("closed"), order)
    }

    @Test
    fun upFromTheTopWrapsToTheLastResult(): Unit = runComposeUiTest {
        val ran = mutableListOf<String>()
        show { CommandBar(items(ran)) { } }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Go to Network"), ran)
    }

    @Test
    fun enterRunsTheFirstResultWhenNothingWasMoved(): Unit = runComposeUiTest {
        val ran = mutableListOf<String>()
        show { CommandBar(items(ran)) { } }
        onNode(hasSetTextAction()).performTextInput("settings")
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Go to Settings"), ran)
    }

    @Test
    fun escapeClosesWithoutRunningAnything(): Unit = runComposeUiTest {
        val ran = mutableListOf<String>()
        var closed = 0
        show { CommandBar(items(ran)) { closed++ } }
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(1, closed)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun clickingAResultRunsIt(): Unit = runComposeUiTest {
        val ran = mutableListOf<String>()
        var closed = 0
        show { CommandBar(items(ran)) { closed++ } }
        onNodeWithText("Go to Settings").performClick()
        waitForIdle()
        assertEquals(listOf("Go to Settings"), ran)
        assertEquals(1, closed)
    }

    @Test
    fun aQueryWithNoMatchSaysSoAndEnterDoesNothing(): Unit = runComposeUiTest {
        val ran = mutableListOf<String>()
        show { CommandBar(items(ran)) { } }
        onNode(hasSetTextAction()).performTextInput("zzzz")
        waitForIdle()
        onNodeWithText("Nothing matches").assertExists()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(ran.isEmpty())
    }
}
