package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Metric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendRecorderTest {
    private val d = DeviceId("dev")
    private val min = 60_000L

    @Test
    fun needsTwoSamplesAndKeepsTheGap() {
        val r = TrendRecorder()
        assertNull(r.trend(d, Metric.HUMIDITY, 0))
        r.record(d, Metric.HUMIDITY, 40.0, 0)
        assertNull(r.trend(d, Metric.HUMIDITY, 0))
        r.record(d, Metric.HUMIDITY, 41.0, 2 * min) // too soon: ignored
        assertNull(r.trend(d, Metric.HUMIDITY, 2 * min))
        r.record(d, Metric.HUMIDITY, 42.0, 6 * min)
        val t = assertNotNull(r.trend(d, Metric.HUMIDITY, 6 * min))
        assertEquals(listOf(40.0, 42.0), t.values)
        assertEquals(6 * min, t.spanMillis)
    }

    @Test
    fun resamplesToAtMost48EvenlySpacedPoints() {
        val r = TrendRecorder()
        for (i in 0 until 200) r.record(d, Metric.INDOOR_TEMPERATURE, 20.0 + i * 0.01, i * 6 * min)
        val t = assertNotNull(r.trend(d, Metric.INDOOR_TEMPERATURE, 199 * 6 * min))
        assertTrue(t.values.size <= 48)
        assertTrue(t.values.zipWithNext().all { (a, b) -> b >= a }) // the series rises, so does the trend
    }

    @Test
    fun oldSamplesFallOutOfTheWindowAndTheSeriesIsBounded() {
        val r = TrendRecorder(windowMs = 60 * min, maxSamples = 50)
        for (i in 0 until 400) r.record(d, Metric.BED, 60.0, i * 6 * min)
        assertTrue(r.export().single().times.size <= 50)
        val t = r.trend(d, Metric.BED, 399 * 6 * min)!!
        assertTrue(t.spanMillis <= 60 * min)
        assertNull(r.trend(d, Metric.BED, 1_000 * 6 * min)) // everything is older than the window
    }

    @Test
    fun badValuesAndClockJumpsAreIgnored() {
        val r = TrendRecorder()
        r.record(d, Metric.HUMIDITY, Double.NaN, 0)
        r.record(d, Metric.HUMIDITY, Double.POSITIVE_INFINITY, 0)
        r.record(d, Metric.HUMIDITY, 1e12, 0)
        assertEquals(0, r.export().size)
        r.record(d, Metric.HUMIDITY, 50.0, 100 * min)
        r.record(d, Metric.HUMIDITY, 51.0, 10 * min) // the clock went backwards
        assertEquals(1, r.export().single().times.size)
    }

    @Test
    fun exportImportRoundTripsAndRejectsDamagedData() {
        val a = TrendRecorder()
        a.record(d, Metric.INDOOR_TEMPERATURE, 20.0, 0); a.record(d, Metric.INDOOR_TEMPERATURE, 21.0, 10 * min)
        val b = TrendRecorder()
        b.import(a.export())
        assertEquals(a.trend(d, Metric.INDOOR_TEMPERATURE, 10 * min), b.trend(d, Metric.INDOOR_TEMPERATURE, 10 * min))

        val c = TrendRecorder()
        c.import(
            listOf(
                TrendRecorder.Exported("x", "NOPE", listOf(0L), listOf(1.0)),
                TrendRecorder.Exported("x", "BED", listOf(0L, 1L), listOf(1.0)),          // length mismatch
                TrendRecorder.Exported("x", "BED", listOf(5L, 1L), listOf(1.0, 2.0)),     // time goes backwards
                TrendRecorder.Exported("x", "BED", listOf(0L, 9L), listOf(1.0, Double.NaN)),
            ),
        )
        assertEquals(0, c.export().size)
    }

    @Test
    fun forgetRemovesOnlyThatDevice() {
        val r = TrendRecorder()
        r.record(d, Metric.BED, 1.0, 0); r.record(DeviceId("other"), Metric.BED, 1.0, 0)
        r.forget(d)
        assertEquals(listOf("other"), r.export().map { it.device })
    }
}
