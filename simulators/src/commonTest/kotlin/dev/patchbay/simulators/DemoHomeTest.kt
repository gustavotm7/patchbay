package dev.patchbay.simulators

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.CommandValidator
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.Metric
import dev.patchbay.core.model.PrintAction
import dev.patchbay.core.result.PatchbayResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DemoHomeTest {

    private val home = DemoHome.home()

    @Test
    fun everyDeviceHasASnapshotAndPlacedDevicesSitInTheirRoom() {
        for (d in home.devices) {
            assertNotNull(home.snapshot(d.id), "snapshot for ${d.id}")
            val p = d.placement ?: continue
            assertEquals(d.roomId, home.floorPlan.roomAt(p)?.id, "${d.name} placed outside its room")
        }
    }

    @Test
    fun roomsDoNotOverlap() {
        val shapes = home.floorPlan.rooms.flatMap { r -> r.shapes.map { r.id to it } }
        for ((i, a) in shapes.withIndex()) for (b in shapes.drop(i + 1)) {
            if (a.first == b.first) continue
            val overlap = a.second.x < b.second.right && b.second.x < a.second.right &&
                a.second.y < b.second.bottom && b.second.y < a.second.bottom
            assertTrue(!overlap, "${a.first} overlaps ${b.first}")
        }
    }

    @Test
    fun demoStateIsValidAgainstCapabilities() {
        val t = home.device(DemoHome.Ids.THERMOSTAT)!!
        val s = home.snapshot(t.id)!!.state<CapabilityState.ThermostatState>()!!
        assertTrue(s.heatSetpoint!! in DemoHome.thermostatCapability.heatRange)
        assertTrue(s.coolSetpoint!! - s.heatSetpoint!! >= DemoHome.thermostatCapability.minimumDeadband)
    }

    @Test
    fun lifeSafetyAndCloudDevicesAreNotControllable() {
        val alarm = home.device(DemoHome.Ids.SMOKE_ALARM)!!
        assertTrue(alarm.lifeSafety && alarm.controlPath == ControlPath.CLOUD_ONLY)
        val r = CommandValidator.validate(alarm, Command.SetPower(false))
        assertTrue(r is PatchbayResult.Err)
        assertTrue(CommandValidator.validate(home.device(DemoHome.Ids.PRINTER)!!, Command.ControlPrint(PrintAction.PAUSE)) is PatchbayResult.Ok)
    }

    @Test
    fun trendsAreDeterministic() {
        val a = DemoHome.trends.trend(DemoHome.Ids.PRINTER, Metric.NOZZLE_1)
        val b = DemoHome.trends.trend(DemoHome.Ids.PRINTER, Metric.NOZZLE_1)
        assertEquals(a, b)
        assertEquals(220.0, a!!.values.last(), 1.0)
    }
}
