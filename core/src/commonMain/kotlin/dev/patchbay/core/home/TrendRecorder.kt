package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Metric
import dev.patchbay.core.model.Trend

/**
 * Keeps a day of readings per device and metric, one sample every few minutes, for the little graphs next to a temperature or a
 * humidity. It lives in memory and is saved by the caller (encrypted with everything else); nothing here touches the network.
 * Bounded on every axis: samples per series, series per device, and the window.
 */
public class TrendRecorder(
    private val minGapMs: Long = 5 * 60_000,
    private val windowMs: Long = 24 * 3_600_000,
    private val maxSamples: Int = 600,
) {
    public class Exported(public val device: String, public val metric: String, public val times: List<Long>, public val values: List<Double>)

    private class Series { val t = ArrayList<Long>(); val v = ArrayList<Double>() }

    private val series = HashMap<String, Series>()

    private fun key(device: DeviceId, metric: Metric) = "${device.value}|${metric.name}"

    /** Adds a sample unless one was taken less than the gap ago. Non-finite or absurd values and times that go backwards are ignored. */
    @Synchronized
    public fun record(device: DeviceId, metric: Metric, value: Double, now: Long) {
        if (!value.isFinite() || value < -1_000.0 || value > 1_000_000.0) return
        val s = series.getOrPut(key(device, metric)) { Series() }
        if (s.t.isNotEmpty()) {
            val last = s.t.last()
            if (now < last || now - last < minGapMs) return
        }
        s.t += now; s.v += value
        while (s.t.isNotEmpty() && (s.t.size > maxSamples || now - s.t.first() > windowMs)) { s.t.removeAt(0); s.v.removeAt(0) }
    }

    /** At most 48 evenly spaced points covering what has been recorded in the window, or null with fewer than two samples. */
    @Synchronized
    public fun trend(device: DeviceId, metric: Metric, now: Long): Trend? {
        val s = series[key(device, metric)] ?: return null
        val from = s.t.indexOfFirst { now - it <= windowMs }
        if (from < 0) return null
        val t = s.t.subList(from, s.t.size)
        val v = s.v.subList(from, s.v.size)
        if (t.size < 2) return null
        val n = minOf(48, t.size)
        val span = t.last() - t.first()
        if (span <= 0) return null
        val out = ArrayList<Double>(n)
        var j = 0
        for (i in 0 until n) {
            val at = t.first() + span * i / (n - 1)
            while (j + 1 < t.size && t[j + 1] <= at) j++
            out += v[j]
        }
        return Trend(out, span)
    }

    @Synchronized
    public fun forget(device: DeviceId) {
        series.keys.removeAll { it.startsWith("${device.value}|") }
    }

    @Synchronized
    public fun export(): List<Exported> = series.entries.filter { it.value.t.isNotEmpty() }.map { (k, s) ->
        Exported(k.substringBefore('|'), k.substringAfter('|'), s.t.toList(), s.v.toList())
    }

    /** Loads saved series; anything malformed or unknown is skipped. */
    @Synchronized
    public fun import(entries: List<Exported>) {
        for (e in entries.take(256)) {
            val metric = Metric.entries.firstOrNull { it.name == e.metric } ?: continue
            if (e.times.size != e.values.size || e.times.size > maxSamples) continue
            if (e.times.zipWithNext().any { (a, b) -> b < a } || e.values.any { !it.isFinite() }) continue
            val s = Series(); s.t += e.times; s.v += e.values
            series[key(DeviceId(e.device), metric)] = s
        }
    }
}
