package dev.patchbay.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.Home
import dev.patchbay.core.model.RectM
import dev.patchbay.core.model.RoomId
import dev.patchbay.ui.designsystem.DeviceGlyph
import dev.patchbay.ui.designsystem.Fill
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.GlyphState
import dev.patchbay.ui.designsystem.HSpace
import dev.patchbay.ui.designsystem.Hairline
import dev.patchbay.ui.designsystem.Legend
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Panel
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.StatusTag
import dev.patchbay.ui.designsystem.Tone
import dev.patchbay.ui.designsystem.ToneMarker
import dev.patchbay.ui.designsystem.VSpace
import dev.patchbay.ui.designsystem.hatch

/**
 * The floor plan: home base for navigation (brief §6 "spatial navigation"). Rooms are the
 * user-drawn rectangles; devices sit at their placements. Tapping a room selects it.
 */
@Composable
fun FloorPlanView(
    home: Home,
    modifier: Modifier = Modifier,
    selectedRoom: RoomId? = null,
    onRoomClick: (RoomId) -> Unit = {},
    showDeviceNames: Boolean = false,
) {
    val plan = home.floorPlan
    val bounds = plan.bounds ?: return
    val c = Patchbay.colors
    val glyph = if (Patchbay.formFactor == FormFactor.PHONE) 22.dp else 24.dp

    BoxWithConstraints(modifier.aspectRatio((bounds.width / bounds.height).toFloat())) {
        val scale: Dp = maxWidth / bounds.width.toFloat()
        fun x(m: Double): Dp = scale * (m - bounds.x).toFloat()
        fun y(m: Double): Dp = scale * (m - bounds.y).toFloat()

        Canvas(Modifier.fillMaxSize()) {
            val px = scale.toPx()
            fun r(rect: RectM) = Pair(
                Offset(((rect.x - bounds.x) * px).toFloat(), ((rect.y - bounds.y) * px).toFloat()),
                Size((rect.width * px).toFloat(), (rect.height * px).toFloat()),
            )
            val wall = 1.5.dp.toPx()
            for (room in plan.rooms) {
                val isSel = room.id == selectedRoom
                for (shape in room.shapes) {
                    val (o, s) = r(shape)
                    drawRect(if (isSel) c.raised else c.surface, o, s)
                }
            }
            for (room in plan.rooms) {
                for (shape in room.shapes) {
                    val (o, s) = r(shape)
                    drawRect(c.outline, o, s, style = Stroke(wall))
                }
                // Erase seams between rectangles of the same room so L-shapes read as one room.
                for ((i, a) in room.shapes.withIndex()) for (b in room.shapes.drop(i + 1)) {
                    seam(a, b)?.let { seamRect ->
                        val (o, s) = r(seamRect)
                        val fill = if (room.id == selectedRoom) c.raised else c.surface
                        drawRect(fill, o + Offset(wall, wall), Size((s.width - 2 * wall).coerceAtLeast(wall), (s.height - 2 * wall).coerceAtLeast(wall)))
                    }
                }
            }
            selectedRoom?.let { id ->
                plan.rooms.firstOrNull { it.id == id }?.shapes?.forEach { shape ->
                    val (o, s) = r(shape)
                    drawRect(c.text, o, s, style = Stroke(2.dp.toPx()))
                }
            }
        }

        // Room legends and headline values.
        for (room in plan.rooms) {
            val b = room.shapes.first()
            Column(
                Modifier
                    .offset(x(b.x) + Space.s, y(b.y) + Space.s)
                    .semantics(mergeDescendants = true) { contentDescription = "Room ${room.name}" }
                    .clickable(role = Role.Button) { onRoomClick(room.id) },
            ) {
                Legend(room.name, color = if (room.id == selectedRoom) c.text else c.textSecondary)
                DeviceViews.roomHeadline(home, room)?.let { PText(it, Patchbay.type.valueS, c.text) }
            }
        }

        for (d in home.devices) {
            val p = d.placement ?: continue
            val s = home.snapshot(d.id)
            val state = DeviceViews.glyphState(d, s)
            Box(
                Modifier
                    .offset(x(p.x) - glyph / 2, y(p.y) - glyph / 2)
                    .size(glyph)
                    .background(if (d.controlPath == ControlPath.CLOUD_ONLY) c.background else Color.Transparent)
                    .semantics { contentDescription = "${d.name}, ${stateWord(state)}, ${DeviceViews.controlPathLabel(d.controlPath)}" },
            ) {
                if (d.controlPath == ControlPath.CLOUD_ONLY) {
                    Canvas(Modifier.fillMaxSize()) { hatch(c.divider, 4.dp.toPx(), 1.dp.toPx()) }
                }
                DeviceGlyph(d.kind, state, Modifier.fillMaxSize().padding(2.dp), emitted = DeviceViews.emittedColor(d, s, c))
                if (d.controlPath == ControlPath.CLOUD_ONLY || d.controlPath == ControlPath.UNKNOWN) {
                    ToneMarker(Tone.ATTENTION, Modifier.size(7.dp).align(Alignment.TopEnd))
                }
            }
            if (showDeviceNames) {
                PText(
                    d.name, Patchbay.type.caption, c.textTertiary,
                    Modifier.offset(x(p.x) - 40.dp, y(p.y) + glyph / 2 + 1.dp).width(80.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** If two axis-aligned rectangles share an edge segment, returns that segment as a thin rect. */
private fun seam(a: RectM, b: RectM): RectM? {
    val eps = 1e-6
    fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Pair<Double, Double>? {
        val lo = maxOf(a0, b0)
        val hi = minOf(a1, b1)
        return if (hi - lo > eps) lo to hi else null
    }
    val t = 0.02
    return when {
        kotlin.math.abs(a.right - b.x) < eps || kotlin.math.abs(b.right - a.x) < eps -> {
            val xEdge = if (kotlin.math.abs(a.right - b.x) < eps) a.right else a.x
            overlap(a.y, a.bottom, b.y, b.bottom)?.let { (lo, hi) -> RectM(xEdge - t, lo, 2 * t, hi - lo) }
        }
        kotlin.math.abs(a.bottom - b.y) < eps || kotlin.math.abs(b.bottom - a.y) < eps -> {
            val yEdge = if (kotlin.math.abs(a.bottom - b.y) < eps) a.bottom else a.y
            overlap(a.x, a.right, b.x, b.right)?.let { (lo, hi) -> RectM(lo, yEdge - t, hi - lo, 2 * t) }
        }
        else -> null
    }
}

private fun stateWord(s: GlyphState) = when (s) {
    GlyphState.ON -> "on"
    GlyphState.OFF -> "off"
    GlyphState.PASSIVE -> "sensor"
    GlyphState.UNMANAGED -> "not controlled"
}

/** Exposure summary: how many devices are local vs depend on a cloud vs unidentified. */
@Composable
fun ExposureStrip(home: Home, modifier: Modifier = Modifier) {
    val e = DeviceViews.exposure(home)
    val c = Patchbay.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        ExposureCount("${e.local + e.afterSetup}", "local", Tone.NEUTRAL)
        ExposureCount("${e.cloudOnly}", "cloud-only", Tone.ATTENTION)
        ExposureCount("${e.unknown}", "unknown", Tone.ATTENTION)
    }
}

@Composable
private fun ExposureCount(n: String, label: String, tone: Tone) {
    val c = Patchbay.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$n $label" }) {
        ToneMarker(tone, Modifier.size(8.dp))
        HSpace(Space.xs + Space.xxs)
        PText(n, Patchbay.type.valueS, c.text)
        HSpace(Space.xs)
        PText(label, Patchbay.type.caption, c.textSecondary)
    }
}

/** Phone home: header, exposure, floor plan, then rooms and things that need attention. */
@Composable
fun HomeScreenPhone(home: Home, nowLabel: String, selectedRoom: RoomId? = null, onRoomClick: (RoomId) -> Unit = {}) {
    val c = Patchbay.colors
    Column(
        Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(horizontal = Space.l),
    ) {
        VSpace(Space.l)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PText(home.name, Patchbay.type.title)
            HSpace(Space.s)
            StatusTag("Demo", Tone.NEUTRAL)
            Fill()
            PText(nowLabel, Patchbay.type.valueS, c.textSecondary)
        }
        VSpace(Space.s)
        ExposureStrip(home)
        VSpace(Space.l)
        FloorPlanView(home, Modifier.fillMaxWidth(), selectedRoom, onRoomClick)
        VSpace(Space.l)
        AttentionPanel(home)
        VSpace(Space.m)
        RoomsPanel(home, onRoomClick)
        VSpace(Space.xl)
    }
}

@Composable
fun AttentionPanel(home: Home, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    val items: List<Device> = home.devices.filter { it.controlPath == ControlPath.UNKNOWN || it.controlPath == ControlPath.CLOUD_ONLY }
    Panel(modifier.fillMaxWidth(), padding = Space.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend("Needs a look")
            Fill()
            PText("${items.size}", Patchbay.type.valueS, c.textSecondary)
        }
        VSpace(Space.s)
        items.forEachIndexed { i, d ->
            if (i > 0) Hairline(Modifier.padding(vertical = Space.xs))
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                DeviceGlyph(d.kind, GlyphState.UNMANAGED, Modifier.size(20.dp))
                HSpace(Space.m)
                Column(Modifier.weight(1f)) {
                    PText(d.name, Patchbay.type.bodyStrong, maxLines = 1)
                    val what = listOfNotNull(d.vendor, d.model).joinToString(" ").ifEmpty { "Unidentified" }
                    PText(what, Patchbay.type.caption, c.textSecondary, maxLines = 1)
                }
                val tag = when {
                    d.lifeSafety -> "Life-safety · untouched"
                    d.controlPath == ControlPath.UNKNOWN -> "Unknown"
                    else -> "Cloud-only"
                }
                StatusTag(tag, Tone.ATTENTION)
            }
        }
    }
}

@Composable
fun RoomsPanel(home: Home, onRoomClick: (RoomId) -> Unit, modifier: Modifier = Modifier) {
    val c = Patchbay.colors
    Panel(modifier.fillMaxWidth(), padding = Space.m) {
        Legend("Rooms")
        VSpace(Space.s)
        home.floorPlan.rooms.forEachIndexed { i, room ->
            if (i > 0) Hairline()
            val devices = home.devicesIn(room.id)
            val on = DeviceViews.activeOutputs(home, room)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onRoomClick(room.id) }.padding(vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PText(room.name, Patchbay.type.body, modifier = Modifier.weight(1f))
                if (on > 0) {
                    ToneMarker(Tone.ACTIVE, Modifier.size(8.dp))
                    HSpace(Space.xs)
                    PText("$on on", Patchbay.type.caption, c.textSecondary)
                    HSpace(Space.m)
                }
                PText("${devices.size}", Patchbay.type.valueS, c.textSecondary)
                HSpace(Space.xs)
                PText(if (devices.size == 1) "device" else "devices", Patchbay.type.caption, c.textTertiary)
                HSpace(Space.m)
                DeviceViews.roomHeadline(home, room)?.let { PText(it, Patchbay.type.valueS, c.text, Modifier.width(64.dp)) }
                    ?: Box(Modifier.width(64.dp))
            }
        }
    }
}
