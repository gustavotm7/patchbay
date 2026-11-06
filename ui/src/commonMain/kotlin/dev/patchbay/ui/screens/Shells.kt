package dev.patchbay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patchbay.core.home.LiveHome
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Home
import dev.patchbay.core.model.RoomId
import dev.patchbay.core.model.TrendSource
import dev.patchbay.ui.designsystem.DeviceGlyph
import dev.patchbay.ui.designsystem.Fill
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.HSpace
import dev.patchbay.ui.designsystem.Hairline
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.Segmented
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VFill
import dev.patchbay.ui.designsystem.VSpace

enum class Section(val label: String) { HOME("Home"), INVENTORY("Inventory"), EXPOSURE("Exposure"), LEDGER("Ledger"), SETTINGS("Settings") }

/** What the right-hand inspector (desktop) or the pushed screen (phone) shows. */
sealed interface Focus {
    data class RoomFocus(val room: RoomId) : Focus
    data class DeviceFocus(val device: DeviceId) : Focus
}

/**
 * Desktop: a proper multi-pane layout — navigation rail, floor plan, inspector — not a stretched
 * phone UI. Keyboard-first: every target is focusable and shows the focus ring.
 */
@Composable
fun DesktopShell(
    home: Home,
    trends: TrendSource,
    focus: Focus,
    nowLabel: String,
    modifier: Modifier = Modifier,
    section: Section = Section.HOME,
    onFocus: (Focus) -> Unit = {},
    onSection: (Section) -> Unit = {},
    live: LiveHome? = null,
) {
    val c = Patchbay.colors
    val selectedRoom = when (focus) {
        is Focus.RoomFocus -> focus.room
        is Focus.DeviceFocus -> home.device(focus.device)?.roomId
    }
    Row(modifier.fillMaxSize().background(c.background)) {
        NavRail(home, section, onSection)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                PText(section.label, Patchbay.type.title)
                if (!(live != null && (section == Section.INVENTORY || section == Section.EXPOSURE))) {
                    HSpace(Space.xl)
                    ExposureStrip(home)
                }
                Fill()
                StatusTag(if (section == Section.INVENTORY && live != null) "Live" else "Demo home", Tone.NEUTRAL)
                HSpace(Space.l)
                PText(nowLabel, Patchbay.type.valueS, c.textSecondary)
            }
            if (section == Section.INVENTORY && live != null) {
                LiveScreenDesktop(live)
                return@Column
            }
            if (section == Section.EXPOSURE && live != null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.xl)) { NetworkScreen(live, Modifier.width(640.dp)) }
                return@Column
            }
            Row(Modifier.fillMaxSize().padding(Space.xl)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
                    // Loose constraints so the plan keeps its aspect ratio and fits either dimension.
                    FloorPlanView(
                        home, Modifier, selectedRoom = selectedRoom,
                        onRoomClick = { onFocus(Focus.RoomFocus(it)) }, showDeviceNames = true,
                    )
                }
                HSpace(Space.xl)
                Column(Modifier.width(408.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Inspector(home, trends, focus, onFocus)
                }
            }
        }
    }
}

@Composable
private fun NavRail(home: Home, section: Section, onSection: (Section) -> Unit) {
    val c = Patchbay.colors
    Column(Modifier.width(220.dp).fillMaxHeight().background(c.surface).padding(vertical = Space.l, horizontal = Space.m)) {
        Column(Modifier.padding(horizontal = Space.m)) {
            PText("Patchbay", Patchbay.type.title)
            PText(home.name, Patchbay.type.caption, c.textSecondary)
        }
        VSpace(Space.xl)
        Section.entries.forEach { s ->
            val sel = s == section
            Row(
                Modifier.fillMaxWidth().height(40.dp)
                    .clip(Shapes.control)
                    .background(if (sel) c.raised else c.surface, Shapes.control)
                    .semantics { selected = sel }
                    .clickable(role = Role.Tab) { onSection(s) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HSpace(Space.m)
                PText(s.label, Patchbay.type.bodyStrong, if (sel) c.text else c.textSecondary)
            }
            VSpace(Space.xxs)
        }
        VFill()
        Column(Modifier.padding(horizontal = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToneMarker(Tone.NEUTRAL, Modifier.size(8.dp))
                HSpace(Space.s)
                PText("LAN only", Patchbay.type.caption, c.textSecondary)
            }
            PText("0 internet connections", Patchbay.type.caption, c.textTertiary)
            VSpace(Space.m)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PText("Ctrl K", Patchbay.type.mono, c.text)
                HSpace(Space.s)
                PText("Command bar", Patchbay.type.caption, c.textSecondary)
            }
        }
    }
}

@Composable
fun Inspector(home: Home, trends: TrendSource, focus: Focus, onFocus: (Focus) -> Unit) {
    when (focus) {
        is Focus.RoomFocus -> RoomInspector(home, focus.room, onFocus)
        is Focus.DeviceFocus -> {
            val d = home.device(focus.device) ?: return
            val s = home.snapshot(d.id) ?: return
            val room = d.roomId?.let { id -> home.floorPlan.rooms.firstOrNull { it.id == id }?.name }
            when {
                s.state<CapabilityState.ThermostatState>() != null -> ThermostatPanel(d, s, trends, room)
                s.state<CapabilityState.PrintJobState>() != null -> PrinterPanel(d, s, trends, room)
                else -> RoomInspector(home, d.roomId ?: return, onFocus)
            }
        }
    }
}

@Composable
fun RoomInspector(home: Home, roomId: RoomId, onFocus: (Focus) -> Unit) {
    val c = Patchbay.colors
    val room = home.floorPlan.rooms.firstOrNull { it.id == roomId } ?: return
    Column {
        PText(room.name, Patchbay.type.title)
        val b = room.bounds
        PText("${Fmt.fixed(room.shapes.sumOf { it.area }, 1)} m² · ${Fmt.fixed(b.width, 1)} × ${Fmt.fixed(b.height, 1)} m", Patchbay.type.caption, c.textSecondary)
        VSpace(Space.l)
        Panel(Modifier.fillMaxWidth(), padding = Space.m) {
            Legend("Devices")
            VSpace(Space.s)
            home.devicesIn(roomId).forEachIndexed { i, d ->
                if (i > 0) Hairline()
                val s = home.snapshot(d.id)
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { onFocus(Focus.DeviceFocus(d.id)) }.padding(vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeviceGlyph(d.kind, DeviceViews.glyphState(d, s), Modifier.size(22.dp), emitted = DeviceViews.emittedColor(d, s, c))
                    HSpace(Space.m)
                    Column(Modifier.weight(1f)) {
                        PText(d.name, Patchbay.type.bodyStrong)
                        PText(DeviceViews.controlPathLabel(d.controlPath), Patchbay.type.caption, c.textSecondary)
                    }
                    PText(summary(s), Patchbay.type.valueS, c.text)
                }
            }
        }
    }
}

private fun summary(s: dev.patchbay.core.model.DeviceSnapshot?): String {
    if (s == null) return ""
    s.state<CapabilityState.PrintJobState>()?.let { return "${Fmt.percent(it.progress.value)} %" }
    s.state<CapabilityState.ThermostatState>()?.let { return "${Fmt.celsius(it.current.value)} °C" }
    s.state<CapabilityState.TemperatureReading>()?.let { return "${Fmt.celsius(it.temperature.value)} °C" }
    s.state<CapabilityState.PowerState>()?.let { p ->
        if (!p.on) return "Off"
        s.state<CapabilityState.BrightnessState>()?.let { return "${Fmt.percent(it.level.value)} %" }
        return "On"
    }
    s.state<CapabilityState.MediaState>()?.let { return it.status.name.lowercase().replaceFirstChar(Char::uppercase) }
    return ""
}

/** Phone: a pushed device screen with a back affordance to its room. */
@Composable
fun DeviceScreenPhone(home: Home, trends: TrendSource, device: DeviceId, onBack: () -> Unit = {}) {
    val c = Patchbay.colors
    val d = home.device(device) ?: return
    val roomName = d.roomId?.let { id -> home.floorPlan.rooms.firstOrNull { it.id == id }?.name }
    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.padding(start = Space.s, top = Space.s).height(Space.touchTarget).clip(Shapes.pill)
                .clickable(role = Role.Button, onClick = onBack).padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PText("‹", Patchbay.type.title, c.textSecondary)
            HSpace(Space.s)
            PText(roomName ?: "Home", Patchbay.type.bodyStrong, c.textSecondary)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l)) {
            Inspector(home, trends, Focus.DeviceFocus(device)) {}
            VSpace(Space.xl)
        }
    }
}

/** Desktop entry point: owns navigation state for the multi-pane shell. */
@Composable
fun DesktopApp(home: Home, trends: TrendSource, nowLabel: String, live: LiveHome? = null) {
    var focus by remember { mutableStateOf<Focus>(Focus.RoomFocus(home.floorPlan.rooms.first().id)) }
    var section by remember { mutableStateOf(if (live != null) Section.INVENTORY else Section.HOME) }
    DesktopShell(home, trends, focus, nowLabel, section = section, onFocus = { focus = it }, onSection = { section = it }, live = live)
}

enum class PhoneTab(val label: String) { DEVICES("Devices"), NETWORK("Network"), PLAN("Demo") }

/** Phone entry point: a bottom tab bar over Devices (tiles), Network (privacy and exposure) and the demo floor plan. */
@Composable
fun PhoneApp(home: Home, trends: TrendSource, nowLabel: String, live: LiveHome? = null) {
    val c = Patchbay.colors
    if (live == null) {
        DemoPhone(home, trends, nowLabel)
        return
    }
    var tab by remember { mutableStateOf(PhoneTab.DEVICES) }
    Column(Modifier.fillMaxSize().background(c.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PhoneTab.DEVICES -> LiveScreenPhone(live)
                PhoneTab.NETWORK -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l)) { NetworkScreen(live) }
                PhoneTab.PLAN -> DemoPhone(home, trends, nowLabel)
            }
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun BottomBar(selected: PhoneTab, onSelect: (PhoneTab) -> Unit) {
    val c = Patchbay.colors
    Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = Space.s, vertical = Space.xs)) {
        PhoneTab.entries.forEach { t ->
            val sel = t == selected
            val color = if (sel) c.text else c.textSecondary
            Column(
                Modifier.weight(1f).height(60.dp).clip(Shapes.control)
                    .semantics { this.selected = sel }
                    .clickable(role = Role.Tab) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                TabIcon(t, sel, color, Modifier.size(24.dp))
                VSpace(Space.xxs)
                PText(t.label, Patchbay.type.caption, color)
            }
        }
    }
}

/** Plain geometric tab icons (no icon font): tiles, a signal ring, a floor plan. Filled when selected. */
@Composable
private fun TabIcon(tab: PhoneTab, selected: Boolean, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val cutout = Patchbay.colors.surface
    androidx.compose.foundation.Canvas(modifier) {
        val sw = 1.75.dp.toPx()
        val st = androidx.compose.ui.graphics.drawscope.Stroke(sw)
        val s = size.minDimension
        when (tab) {
            PhoneTab.DEVICES -> for (i in 0..1) for (j in 0..1) {
                val tl = androidx.compose.ui.geometry.Offset(s * (0.1f + 0.46f * i), s * (0.1f + 0.46f * j))
                val sz = androidx.compose.ui.geometry.Size(s * 0.34f, s * 0.34f)
                val r = androidx.compose.ui.geometry.CornerRadius(s * 0.09f)
                if (selected) drawRoundRect(color, tl, sz, r) else drawRoundRect(color, tl, sz, r, style = st)
            }
            PhoneTab.NETWORK -> {
                drawCircle(color, s * 0.42f - sw / 2, style = st)
                drawCircle(color, s * 0.14f)
            }
            PhoneTab.PLAN -> {
                val tl = androidx.compose.ui.geometry.Offset(s * 0.1f, s * 0.14f)
                val sz = androidx.compose.ui.geometry.Size(s * 0.8f, s * 0.72f)
                val r = androidx.compose.ui.geometry.CornerRadius(s * 0.08f)
                if (selected) drawRoundRect(color, tl, sz, r) else drawRoundRect(color, tl, sz, r, style = st)
                val line = if (selected) cutout else color
                drawLine(line, androidx.compose.ui.geometry.Offset(s * 0.42f, s * 0.14f), androidx.compose.ui.geometry.Offset(s * 0.42f, s * 0.86f), sw)
                drawLine(line, androidx.compose.ui.geometry.Offset(s * 0.42f, s * 0.5f), androidx.compose.ui.geometry.Offset(s * 0.9f, s * 0.5f), sw)
            }
        }
    }
}

@Composable
private fun DemoPhone(home: Home, trends: TrendSource, nowLabel: String) {
    var focus by remember { mutableStateOf<Focus?>(null) }
    val c = Patchbay.colors
    when (val f = focus) {
        null -> HomeScreenPhone(home, nowLabel, onRoomClick = { focus = Focus.RoomFocus(it) })
        is Focus.RoomFocus -> Column(Modifier.fillMaxSize().background(c.background)) {
            Row(
                Modifier.padding(start = Space.s, top = Space.s).height(Space.touchTarget).clip(Shapes.pill)
                    .clickable(role = Role.Button) { focus = null }.padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PText("‹", Patchbay.type.title, c.textSecondary)
                HSpace(Space.s)
                PText(home.name, Patchbay.type.bodyStrong, c.textSecondary)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.l)) {
                RoomInspector(home, f.room) { focus = it }
            }
        }
        is Focus.DeviceFocus -> DeviceScreenPhone(home, trends, f.device) {
            focus = home.device(f.device)?.roomId?.let { Focus.RoomFocus(it) }
        }
    }
}
