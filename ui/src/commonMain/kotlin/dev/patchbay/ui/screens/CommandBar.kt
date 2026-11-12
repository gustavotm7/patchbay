package dev.patchbay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.VSpace

/** One thing the command bar can do: a title to search, a short hint shown beside it, and the action. */
internal class PaletteItem(val title: String, val hint: String, val run: () -> Unit)

internal object Palette {
    /** Lower is better; null means no match. A title that starts with the query beats one that contains it, which beats letters in order. */
    fun score(query: String, title: String): Int? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val t = title.lowercase()
        if (t.startsWith(q)) return 0
        val at = t.indexOf(q)
        if (at >= 0) return 1 + at
        var from = 0
        var gaps = 0
        for (ch in q) {
            val i = t.indexOf(ch, from)
            if (i < 0) return null
            gaps += i - from
            from = i + 1
        }
        return 100 + gaps
    }

    fun filter(items: List<PaletteItem>, query: String, limit: Int = 8): List<PaletteItem> =
        items.mapIndexedNotNull { i, item -> score(query, item.title)?.let { Triple(it, i, item) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit).map { it.third }
}

/**
 * Ctrl+K: type to find a device, a scene or a section, Enter to do it. Up and Down move, Escape closes. Mouse works too.
 * Nothing runs until Enter or a click, and the actions are the same ones the screens offer (no extra powers).
 */
@Composable
internal fun CommandBar(items: List<PaletteItem>, onClose: () -> Unit) {
    val c = Patchbay.colors
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val results = remember(query, items) { Palette.filter(items, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(results.size) { if (selected >= results.size) selected = (results.size - 1).coerceAtLeast(0) }
    fun run(item: PaletteItem) { onClose(); item.run() }

    Box(
        Modifier.fillMaxSize().background(c.background.copy(alpha = 0.7f)).clickable(onClick = onClose),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 96.dp).width(560.dp).clip(Shapes.panel).background(c.surface, Shapes.panel)
                .border(1.dp, c.divider, Shapes.panel).clickable(enabled = false) {}.padding(Space.m),
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(60); selected = 0 },
                singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(Space.m).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> { onClose(); true }
                        Key.DirectionDown -> { if (results.isNotEmpty()) selected = (selected + 1) % results.size; true }
                        Key.DirectionUp -> { if (results.isNotEmpty()) selected = (selected - 1 + results.size) % results.size; true }
                        Key.Enter, Key.NumPadEnter -> { results.getOrNull(selected)?.let(::run); true }
                        else -> false
                    }
                },
                decorationBox = { inner ->
                    Box { if (query.isEmpty()) PText("Search devices, scenes and screens", Patchbay.type.valueM, c.textTertiary); inner() }
                },
            )
            VSpace(Space.xs)
            if (results.isEmpty()) {
                PText("Nothing matches", Patchbay.type.body, c.textSecondary, Modifier.padding(Space.m))
            }
            results.forEachIndexed { i, item ->
                Row(
                    Modifier.fillMaxWidth().height(40.dp).clip(Shapes.control)
                        .background(if (i == selected) c.raised else c.surface, Shapes.control)
                        .clickable(role = Role.Button) { run(item) }.padding(horizontal = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    PText(item.title, Patchbay.type.bodyStrong, if (i == selected) c.text else c.textSecondary, Modifier.weight(1f), maxLines = 1)
                    PText(item.hint, Patchbay.type.caption, c.textTertiary)
                }
            }
            VSpace(Space.xs)
            PText("Enter to run  ·  Up and Down to choose  ·  Esc to close", Patchbay.type.caption, c.textTertiary, Modifier.padding(horizontal = Space.m))
        }
    }
}
