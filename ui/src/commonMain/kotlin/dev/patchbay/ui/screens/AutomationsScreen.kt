package dev.patchbay.ui.screens

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.patchbay.core.home.ExposureReport
import dev.patchbay.core.home.LiveDevice
import dev.patchbay.core.home.LiveHome
import dev.patchbay.core.home.ScanState
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.Reachability
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.DeviceGlyph
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.HSpace
import dev.patchbay.ui.designsystem.Hairline
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.PToggle
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Segmented
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.launch

// Scenes, schedules and away mode (the things you set up once).

/** Delete needs a second tap within three seconds, so it can't be hit by accident next to Run. */
@Composable
internal fun ConfirmingDelete(what: String, onConfirm: () -> Unit) {
    val c = Patchbay.colors
    var armed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(3_000); armed = false } }
    Box(
        Modifier.height(Space.touchTarget).clip(Shapes.pill)
            .clickable(role = Role.Button, onClickLabel = if (armed) "Confirm delete $what" else "Delete $what") { if (armed) onConfirm() else armed = true }
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.Center,
    ) { PText(if (armed) "Tap again to delete" else "Delete", Patchbay.type.caption, if (armed) c.alarm else c.textSecondary) }
}

/** Scenes, schedules and away mode: the things you set up once, kept off the home screen. */
@Composable
fun AutomationsScreen(live: LiveHome, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    Column(modifier) {
        PText("Automations", Patchbay.type.largeTitle)
        PText("Scenes, schedules and away mode.", Patchbay.type.body, c.textSecondary)
        VSpace(Space.l)
        AwayPanel(live)
        ScenesPanel(live)
        AutomationsPanel(live)
    }
}

/** Saved scenes with a Run button each, and a way to make a new one from the lights and plugs as they are right now. */
@Composable
internal fun ScenesPanel(live: LiveHome) {
    val c = Patchbay.colors
    val scenes by live.scenes.collectAsState()
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val eligible = devices.filter {
        (it.device.kind == DeviceKind.LIGHT || it.device.kind == DeviceKind.PLUG) &&
            it.snapshot?.reachability == Reachability.ONLINE && it.snapshot?.state<CapabilityState.PowerState>() != null ||
            it.device.kind == DeviceKind.THERMOSTAT && it.snapshot?.reachability == Reachability.ONLINE && it.snapshot?.state<CapabilityState.ThermostatState>() != null ||
            (it.device.kind == DeviceKind.TV || it.device.kind == DeviceKind.MEDIA_RECEIVER || it.device.kind == DeviceKind.SPEAKER) &&
            it.snapshot?.reachability == Reachability.ONLINE && it.snapshot?.state<CapabilityState.VolumeState>() != null
    }
    if (scenes.isEmpty() && eligible.isEmpty()) return
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<DeviceId>()) }
    var message by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Scenes")
                if (scenes.isEmpty() && !creating) PText("One tap sets several lights and plugs the way they are now.", Patchbay.type.caption, c.textSecondary)
            }
            if (!creating && eligible.isNotEmpty()) {
                PButton("All off", {
                    scope.launch {
                        message = when (val r = live.allOff()) {
                            is dev.patchbay.core.result.PatchbayResult.Ok -> if (r.value == 0) "Everything is already off" else "Switched off ${r.value}"
                            is dev.patchbay.core.result.PatchbayResult.Err -> r.error.message
                        }
                    }
                })
                HSpace(Space.s)
                PButton("New scene", { creating = true; message = null; picked = eligible.map { it.device.id }.toSet() })
            }
        }
        scenes.forEach { sc ->
            VSpace(Space.s)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PText(sc.name, Patchbay.type.bodyStrong)
                    PText("${sc.devices.size} devices", Patchbay.type.caption, c.textSecondary)
                }
                ConfirmingDelete(sc.name) { scope.launch { live.deleteScene(sc.id) } }
                HSpace(Space.xs)
                PButton("Run", {
                    scope.launch {
                        message = when (val r = live.runScene(sc.id)) {
                            is dev.patchbay.core.result.PatchbayResult.Ok -> "${sc.name}: done"
                            is dev.patchbay.core.result.PatchbayResult.Err -> r.error.message
                        }
                    }
                }, kind = ButtonKind.PRIMARY)
            }
        }
        message?.let {
            VSpace(Space.s)
            PText(it, Patchbay.type.caption, c.textSecondary)
        }
        if (creating) {
            VSpace(Space.m)
            PText("Name", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            BasicTextField(
                value = name,
                onValueChange = { v -> name = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(32) },
                singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.m),
            )
            VSpace(Space.s)
            PText("Include", Patchbay.type.bodyStrong)
            eligible.forEach { d ->
                PToggle(d.device.name, d.device.id in picked, { on -> picked = if (on) picked + d.device.id else picked - d.device.id })
            }
            VSpace(Space.s)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton(
                    "Save scene", {
                        scope.launch {
                            when (val r = live.saveScene(name, picked.toList())) {
                                is dev.patchbay.core.result.PatchbayResult.Ok -> { creating = false; name = ""; message = null }
                                is dev.patchbay.core.result.PatchbayResult.Err -> message = r.error.message
                            }
                        }
                    },
                    Modifier.weight(1f), ButtonKind.PRIMARY, enabled = name.isNotBlank() && picked.isNotEmpty(),
                )
                PButton("Cancel", { creating = false; message = null }, Modifier.weight(1f))
            }
            VSpace(Space.xs)
            PText("Saves each device's power, brightness and colour as they are right now.", Patchbay.type.caption, c.textTertiary)
        }
    }
    VSpace(Space.l)
}

internal val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

internal fun daysText(days: Set<Int>): String = when {
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
}

/** Run a scene at a time on chosen days. Only while Patchbay is open or in the tray; the panel says so. */
@Composable
internal fun AutomationsPanel(live: LiveHome) {
    val c = Patchbay.colors
    val automations by live.automations.collectAsState()
    val scenes by live.scenes.collectAsState()
    val scope = rememberCoroutineScope()
    if (scenes.isEmpty() && automations.isEmpty()) return
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("07:30") }
    var sceneId by remember { mutableStateOf<String?>(null) }
    var whenKind by remember { mutableStateOf("time") }
    var offset by remember { mutableStateOf("0") }
    val location by live.location.collectAsState()
    var days by remember { mutableStateOf(setOf(1, 2, 3, 4, 5, 6, 7)) }
    var error by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Legend("Automations")
                PText("Run a scene at a set time or at sunrise or sunset. Runs while Patchbay is open, in the tray, or watching in the background (Settings).", Patchbay.type.caption, c.textSecondary)
            }
            if (!creating && scenes.isNotEmpty()) PButton("New", { creating = true; error = null; sceneId = scenes.first().id })
        }
        automations.forEach { a ->
            VSpace(Space.s)
            PToggle(a.name, a.enabled, { on -> scope.launch { live.setAutomationEnabled(a.id, on) } })
            Row(verticalAlignment = Alignment.CenterVertically) {
                PText(
                    (a.sun?.let { sunName -> sunName.replaceFirstChar { ch -> ch.uppercase() } + if (a.offsetMinutes != 0) " %+d min".format(a.offsetMinutes) else "" } ?: "%02d:%02d".format(a.hour, a.minute)) +
                        " · ${daysText(a.days)} · ${a.sceneName ?: "scene deleted"}",
                    Patchbay.type.caption, if (a.sceneName == null) c.attention else c.textSecondary, Modifier.weight(1f),
                )
                ConfirmingDelete(a.name) { scope.launch { live.deleteAutomation(a.id) } }
            }
        }
        if (creating) {
            VSpace(Space.m)
            PText("Name", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            BasicTextField(
                value = name, onValueChange = { v -> name = v.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(32) }, singleLine = true,
                textStyle = Patchbay.type.valueM.copy(color = c.text),
                modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.m),
            )
            VSpace(Space.s)
            PText("Scene", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            scenes.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { sc -> PButton(sc.name, { sceneId = sc.id }, Modifier.weight(1f), if (sc.id == sceneId) ButtonKind.PRIMARY else ButtonKind.SECONDARY) }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
                VSpace(Space.xs)
            }
            VSpace(Space.xs)
            PText("When", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            Segmented(listOf("time", "sunrise", "sunset"), whenKind, { it.replaceFirstChar { ch -> ch.uppercase() } }, { whenKind = it }, Modifier.fillMaxWidth())
            VSpace(Space.s)
            if (whenKind == "time") {
                PText("Time (24 hour, HH:MM)", Patchbay.type.bodyStrong)
                VSpace(Space.xs)
                BasicTextField(
                    value = time, onValueChange = { v -> time = v.filter { it.isDigit() || it == ':' }.take(5) }, singleLine = true,
                    textStyle = Patchbay.type.valueM.copy(color = c.text),
                    modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.m),
                )
            } else if (location == null) {
                PText("Set your home location in Settings first. It's typed by you and stays on this device.", Patchbay.type.caption, c.attention)
            } else {
                PText("Minutes after (use a minus sign for before)", Patchbay.type.bodyStrong)
                VSpace(Space.xs)
                BasicTextField(
                    value = offset, onValueChange = { v -> offset = v.filter { it.isDigit() || it == '-' }.take(4) }, singleLine = true,
                    textStyle = Patchbay.type.valueM.copy(color = c.text),
                    modifier = Modifier.fillMaxWidth().border(Space.hair, c.outline, Shapes.control).padding(Space.m),
                )
            }
            VSpace(Space.s)
            PText("Days", Patchbay.type.bodyStrong)
            VSpace(Space.xs)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                DAY_LETTERS.forEachIndexed { i, letter ->
                    val d = i + 1
                    PButton(letter, { days = if (d in days) days - d else days + d }, Modifier.weight(1f), if (d in days) ButtonKind.PRIMARY else ButtonKind.SECONDARY)
                }
            }
            error?.let {
                VSpace(Space.xs)
                PText(it, Patchbay.type.caption, c.alarm)
            }
            VSpace(Space.s)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PButton(
                    "Save", {
                        val parts = time.split(':')
                        val h = parts.getOrNull(0)?.toIntOrNull()
                        val m = parts.getOrNull(1)?.toIntOrNull()
                        val off = offset.toIntOrNull()
                        val sid = sceneId
                        if (whenKind == "time" && (h == null || m == null || parts.size != 2)) error = "Enter the time as HH:MM, for example 07:30"
                        else if (whenKind != "time" && off == null) error = "Enter the offset in minutes, for example 30 or -15"
                        else if (sid == null) error = "Pick a scene"
                        else scope.launch {
                            when (val r = live.saveAutomation(name, sid, h ?: 0, m ?: 0, days, if (whenKind == "time") null else whenKind, off ?: 0)) {
                                is dev.patchbay.core.result.PatchbayResult.Ok -> { creating = false; name = ""; error = null }
                                is dev.patchbay.core.result.PatchbayResult.Err -> error = r.error.message
                            }
                        }
                    },
                    Modifier.weight(1f), ButtonKind.PRIMARY, enabled = name.isNotBlank(),
                )
                PButton("Cancel", { creating = false; error = null }, Modifier.weight(1f))
            }
        }
    }
    VSpace(Space.l)
}

/** Away mode: everything off now, and optionally the lights imitate someone being home in the evening. */
@Composable
internal fun AwayPanel(live: LiveHome) {
    val c = Patchbay.colors
    val away by live.away.collectAsState()
    val devices by live.devices.collectAsState()
    val scope = rememberCoroutineScope()
    val lights = devices.filter { it.device.kind == DeviceKind.LIGHT }.map { it.device.id }
    if (lights.isEmpty() && !away.active) return
    var simulate by remember { mutableStateOf(true) }
    Panel(Modifier.fillMaxWidth()) {
        Legend("Away mode")
        VSpace(Space.xs)
        PToggle("Away", away.active, { on -> scope.launch { live.setAway(on, if (on && simulate) lights.toSet() else emptySet()) } })
        if (!away.active) {
            PToggle("Imitate someone home with my lights", simulate, { simulate = it })
        }
        VSpace(Space.xs)
        PText(
            if (away.active) "Away is on. Everything was switched off${if (away.simulate.isNotEmpty()) "; lights switch on and off between 18:00 and 23:00 as if someone were home" else ""}."
            else "Turning it on switches everything off now. With the imitation on, lights flip at irregular times in the evening and go off at 23:00. It runs only while Patchbay is open or in the tray.",
            Patchbay.type.caption, c.textSecondary,
        )
    }
    VSpace(Space.l)
}
