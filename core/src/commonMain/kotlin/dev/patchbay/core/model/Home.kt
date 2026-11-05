package dev.patchbay.core.model

import kotlin.jvm.JvmInline

@JvmInline
public value class RoomId(public val value: String)

/** An axis-aligned rectangle in metres on the floor plan (origin top-left, y grows downward). */
public data class RectM(val x: Double, val y: Double, val width: Double, val height: Double) {
    init {
        require(listOf(x, y, width, height).all { it.isFinite() }) { "rect must be finite" }
        require(width > 0.0 && height > 0.0) { "rect must have positive size" }
    }

    val right: Double get() = x + width
    val bottom: Double get() = y + height
    val area: Double get() = width * height

    public operator fun contains(p: Placement): Boolean = p.x in x..right && p.y in y..bottom
}

/** A line segment in metres on the floor plan. */
public data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

public data class Room(
    val id: RoomId,
    val name: String,
    /** A room is one or more rectangles (L-shapes are two). Rectangles are enough per the brief. */
    val shapes: List<RectM>,
) {
    init {
        require(shapes.isNotEmpty()) { "room needs at least one rectangle" }
    }

    /**
     * The room's outline as axis-aligned segments: every rectangle edge minus the parts shared with
     * another rectangle of the same room. An L-shape made of two rectangles has no line through it.
     */
    public fun outline(): List<Segment> {
        val out = ArrayList<Segment>()
        for (r in shapes) {
            // top, bottom: horizontal; left, right: vertical. For each, which side is "outside".
            out += subtract(Segment(r.x, r.y, r.right, r.y)) { o, a -> o.x <= a && a <= o.right && o.y < r.y && r.y <= o.bottom }
            out += subtract(Segment(r.x, r.bottom, r.right, r.bottom)) { o, a -> o.x <= a && a <= o.right && o.y <= r.bottom && r.bottom < o.bottom }
            out += subtract(Segment(r.x, r.y, r.x, r.bottom)) { o, a -> o.y <= a && a <= o.bottom && o.x < r.x && r.x <= o.right }
            out += subtract(Segment(r.right, r.y, r.right, r.bottom)) { o, a -> o.y <= a && a <= o.bottom && o.x <= r.right && r.right < o.right }
        }
        return out
    }

    /** Removes the parts of [edge] whose outside neighbourhood is covered by another shape. */
    private fun subtract(edge: Segment, covers: (RectM, Double) -> Boolean): List<Segment> {
        val horizontal = edge.y1 == edge.y2
        val lo = if (horizontal) edge.x1 else edge.y1
        val hi = if (horizontal) edge.x2 else edge.y2
        // Interval arithmetic over the edge, using the other shapes' extents along it.
        var free = listOf(lo to hi)
        for (o in shapes) {
            val (a, b) = if (horizontal) o.x to o.right else o.y to o.bottom
            val mid = (maxOf(a, lo) + minOf(b, hi)) / 2
            if (minOf(b, hi) - maxOf(a, lo) <= 1e-9 || !covers(o, mid)) continue
            free = free.flatMap { (f0, f1) ->
                listOfNotNull(
                    (f0 to minOf(f1, a)).takeIf { it.second - it.first > 1e-9 },
                    (maxOf(f0, b) to f1).takeIf { it.second - it.first > 1e-9 },
                )
            }
        }
        return free.map { (a, b) -> if (horizontal) Segment(a, edge.y1, b, edge.y1) else Segment(edge.x1, a, edge.x1, b) }
    }

    val bounds: RectM
        get() {
            val l = shapes.minOf { it.x }
            val t = shapes.minOf { it.y }
            return RectM(l, t, shapes.maxOf { it.right } - l, shapes.maxOf { it.bottom } - t)
        }
}

public data class FloorPlan(val rooms: List<Room>) {
    val bounds: RectM?
        get() = if (rooms.isEmpty()) null else {
            val all = rooms.flatMap { it.shapes }
            val l = all.minOf { it.x }
            val t = all.minOf { it.y }
            RectM(l, t, all.maxOf { it.right } - l, all.maxOf { it.bottom } - t)
        }

    public fun roomAt(p: Placement): Room? = rooms.firstOrNull { r -> r.shapes.any { p in it } }
}

/** The user's home: layout, device registry and the latest snapshot per device. */
public data class Home(
    val name: String,
    val floorPlan: FloorPlan,
    val devices: List<Device>,
    val snapshots: Map<DeviceId, DeviceSnapshot>,
) {
    public fun devicesIn(room: RoomId): List<Device> = devices.filter { it.roomId == room }

    public fun device(id: DeviceId): Device? = devices.firstOrNull { it.id == id }

    public fun snapshot(id: DeviceId): DeviceSnapshot? = snapshots[id]
}

/** A short time series for inline sparklines, oldest first, evenly spaced over [spanMillis]. */
public data class Trend(val values: List<Double>, val spanMillis: Long) {
    init {
        require(values.size <= MAX_POINTS) { "trend too long" }
        require(values.all { it.isFinite() }) { "trend values must be finite" }
    }

    public companion object {
        public const val MAX_POINTS: Int = 512
    }
}

/** Metrics that can have an inline trend next to their value. */
public enum class Metric { INDOOR_TEMPERATURE, HUMIDITY, NOZZLE_1, NOZZLE_2, BED, CHAMBER }

/** Supplies recent history for sparklines. Backed by the state ledger (Phase 2) or a simulator. */
public fun interface TrendSource {
    public fun trend(device: DeviceId, metric: Metric): Trend?
}
