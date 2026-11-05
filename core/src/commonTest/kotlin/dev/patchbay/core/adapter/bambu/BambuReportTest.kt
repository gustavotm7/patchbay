package dev.patchbay.core.adapter.bambu

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.PrintAction
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.model.Severity
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.errorOrNull
import dev.patchbay.core.result.getOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** H2D-shaped report written for these tests (structure matches what the printer sends; values invented). */
object BambuFixtures {
    // 0x00DC00DB = target 220, current 219; 0x00000026 = 38 °C, no target; 0x00410041 = bed 65/65.
    const val H2D_PUSHALL: String = """{"print":{"command":"push_status","gcode_state":"RUNNING","mc_percent":47,
        "layer_num":112,"total_layer_num":240,"mc_remaining_time":96,"subtask_name":"enclosure_bracket_v3",
        "nozzle_temper":219.0,"nozzle_target_temper":220.0,"bed_temper":65.0,"bed_target_temper":65.0,"chamber_temper":34.0,
        "hms":[],"lights_report":[{"node":"chamber_light","mode":"on"},{"node":"chamber_light2","mode":"on"}],
        "device":{"extruder":{"info":[{"id":0,"temp":14418139},{"id":1,"temp":38}],"state":2},
                  "bed":{"info":{"temp":4259905}},"ctc":{"info":{"temp":34}}},
        "ams":{"tray_now":"1","ams":[{"id":"0","humidity":"5","temp":"27.0","tray":[
            {"id":"0","tray_type":"PLA","tray_color":"F2F2EEFF","remain":80},
            {"id":"1","tray_type":"PETG","tray_color":"1F1F22FF","remain":55},
            {"id":"2","tray_type":"PLA","tray_color":"E04B2AFF","remain":12},
            {"id":"3","tray_type":"","tray_color":"00000000","remain":-1}]}]}}}"""
}

class BambuReportTest {
    private fun parse(s: String) = BambuReport.parse(s.encodeToByteArray()).getOrNull()!!

    @Test
    fun decodesH2dDualNozzlePackedTemperatures() {
        val st = BambuReport.toStates(parse(BambuFixtures.H2D_PUSHALL))
        val th = st.filterIsInstance<CapabilityState.PrinterThermalsState>().single()
        assertEquals(2, th.nozzles.size)
        assertEquals(219.0, th.nozzles[0].current.value); assertEquals(220.0, th.nozzles[0].target!!.value)
        assertEquals(38.0, th.nozzles[1].current.value); assertNull(th.nozzles[1].target)
        assertEquals(65.0, th.bed.current.value); assertEquals(65.0, th.bed.target!!.value)
        assertEquals(34.0, th.chamber!!.current.value)
    }

    @Test
    fun jobAmsAndLight() {
        val st = BambuReport.toStates(parse(BambuFixtures.H2D_PUSHALL))
        val job = st.filterIsInstance<CapabilityState.PrintJobState>().single()
        assertEquals(PrintStage.PRINTING, job.stage)
        assertEquals(47.0, job.progress.value); assertEquals(112, job.layer); assertEquals(240, job.totalLayers); assertEquals(96, job.remainingMinutes)
        val slots = st.filterIsInstance<CapabilityState.FilamentSlotsState>().single().slots
        assertEquals(4, slots.size)
        assertEquals(Rgb(0x1F, 0x1F, 0x22), slots[1].colour)
        assertTrue(slots[1].active)
        assertNull(slots[3].material) // empty slot
        assertEquals(true, st.filterIsInstance<CapabilityState.AuxLightState>().single().on)
    }

    @Test
    fun incrementalUpdatesMergeIntoState() {
        var state = parse(BambuFixtures.H2D_PUSHALL)
        state = BambuReport.merge(state, parse("""{"print":{"mc_percent":48,"layer_num":115,"device":{"bed":{"info":{"temp":4259904}}}}}"""))
        val st = BambuReport.toStates(state)
        val job = st.filterIsInstance<CapabilityState.PrintJobState>().single()
        assertEquals(48.0, job.progress.value); assertEquals(115, job.layer); assertEquals(240, job.totalLayers) // kept
        val th = st.filterIsInstance<CapabilityState.PrinterThermalsState>().single()
        assertEquals(64.0, th.bed.current.value)
        assertEquals(2, th.nozzles.size) // the extruder block survived the merge
    }

    @Test
    fun olderModelsWithoutPackedFields() {
        val st = BambuReport.toStates(parse("""{"print":{"gcode_state":"PAUSE","nozzle_temper":200.5,"nozzle_target_temper":0,"bed_temper":55,"bed_target_temper":55}}"""))
        val th = st.filterIsInstance<CapabilityState.PrinterThermalsState>().single()
        assertEquals(1, th.nozzles.size); assertNull(th.nozzles[0].target)
        assertEquals(PrintStage.PAUSED, st.filterIsInstance<CapabilityState.PrintJobState>().single().stage)
    }

    @Test
    fun hmsCodesAndSeverity() {
        val e = BambuReport.hmsEvent(0x0300_0100L, 0x0001_0001L)
        assertEquals("HMS_0300_0100_0001_0001", e.code)
        assertEquals(Severity.FAULT, e.severity)
        val st = BambuReport.toStates(parse("""{"print":{"gcode_state":"RUNNING","hms":[{"attr":50331904,"code":196609}]}}"""))
        assertEquals(Severity.WARNING, st.filterIsInstance<CapabilityState.PrintJobState>().single().hms.single().severity)
    }

    @Test
    fun implausibleValuesAreDropped() {
        assertNull(BambuReport.unpack(0x0FFF_0FFFL)) // 4095 °C
        val st = BambuReport.toStates(parse("""{"print":{"gcode_state":"RUNNING","mc_percent":250,"layer_num":-3,"total_layer_num":10}}"""))
        val job = st.filterIsInstance<CapabilityState.PrintJobState>().single()
        assertEquals(0.0, job.progress.value); assertEquals(0, job.layer)
    }

    @Test
    fun commandsAreOnlyTheAllowedOnes() {
        assertEquals("""{"print":{"sequence_id":"0","command":"pause"}}""", BambuReport.control(PrintAction.PAUSE).decodeToString())
        assertEquals("""{"pushing":{"sequence_id":"0","command":"pushall"}}""", BambuReport.pushAll.decodeToString())
        assertTrue("\"led_mode\":\"off\"" in BambuReport.light("chamber_light", false).decodeToString())
        assertFailsWith<IllegalArgumentException> { BambuReport.light("heatbed", true) }
        assertFailsWith<IllegalArgumentException> { BambuReport.reportTopic("../#") }
        assertEquals("device/0948AD4A0B00001/report", BambuReport.reportTopic("0948AD4A0B00001"))
    }

    @Test
    fun rejectsGarbageAndDeepNesting() {
        listOf("", "[]", "nope", "[".repeat(10_000)).forEach { assertIs<PatchbayError.ProtocolViolation>(BambuReport.parse(it.encodeToByteArray()).errorOrNull(), it.take(20)) }
    }

    @Test
    fun neverThrowsOnMutatedInput() {
        val seed = BambuFixtures.H2D_PUSHALL.encodeToByteArray()
        val rnd = Random(0xBAB)
        var state = parse(BambuFixtures.H2D_PUSHALL)
        repeat(5_000) {
            val b = seed.copyOf()
            repeat(rnd.nextInt(1, 10)) { b[rnd.nextInt(b.size)] = (32 + rnd.nextInt(95)).toByte() }
            BambuReport.parse(b).getOrNull()?.let { u -> state = BambuReport.merge(state, u); BambuReport.toStates(state) }
        }
    }
}
