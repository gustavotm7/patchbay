package dev.patchbay.core.model

import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.errorOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandValidatorTest {

    private fun device(vararg caps: Capability, path: ControlPath = ControlPath.LOCAL, lifeSafety: Boolean = false) = Device(
        id = DeviceId("d1"), name = "Test", kind = DeviceKind.UNKNOWN, vendor = null, model = null,
        roomId = null, placement = null, controlPath = path, capabilities = caps.toList(),
        adapter = AdapterId("test"), lifeSafety = lifeSafety,
    )

    private val thermostat = Capability.Thermostat(
        modes = setOf(HvacMode.OFF, HvacMode.HEAT, HvacMode.COOL, HvacMode.AUTO),
        heatRange = Celsius(7.0)..Celsius(26.0),
        coolRange = Celsius(18.0)..Celsius(33.0),
        minimumDeadband = 2.0,
    )

    private fun snapshotWith(heat: Double, cool: Double) = DeviceSnapshot(
        DeviceId("d1"), Reachability.ONLINE,
        listOf(CapabilityState.ThermostatState(HvacMode.AUTO, HvacAction.IDLE, Celsius(21.0), Celsius(heat), Celsius(cool))),
        EpochMillis(0),
    )

    private fun PatchbayResult<Unit>.isOk() = this is PatchbayResult.Ok

    @Test
    fun acceptsSetpointsWithinRangeAndDeadband() {
        val d = device(thermostat)
        assertTrue(CommandValidator.validate(d, Command.SetSetpoints(Celsius(20.0), Celsius(24.0))).isOk())
    }

    @Test
    fun rejectsDeadbandViolationAgainstCurrentState() {
        val d = device(thermostat)
        val r = CommandValidator.validate(d, Command.SetSetpoints(heat = Celsius(23.0), cool = null), snapshotWith(20.0, 24.0))
        assertIs<PatchbayError.InvalidArgument>(r.errorOrNull())
        // Exactly at the minimum deadband is fine.
        assertTrue(CommandValidator.validate(d, Command.SetSetpoints(heat = Celsius(22.0), cool = null), snapshotWith(20.0, 24.0)).isOk())
    }

    @Test
    fun rejectsSetpointsOutsideRange() {
        val d = device(thermostat)
        assertIs<PatchbayError.InvalidArgument>(CommandValidator.validate(d, Command.SetSetpoints(Celsius(30.0), null)).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(CommandValidator.validate(d, Command.SetSetpoints(null, Celsius(10.0))).errorOrNull())
        assertIs<PatchbayError.InvalidArgument>(CommandValidator.validate(d, Command.SetSetpoints(null, null)).errorOrNull())
    }

    @Test
    fun fanDetents() {
        val d = device(Capability.FanSpeed(levels = 4))
        assertTrue(CommandValidator.validate(d, Command.SetFanSpeed(0)).isOk())
        assertTrue(CommandValidator.validate(d, Command.SetFanSpeed(4)).isOk())
        assertIs<PatchbayError.InvalidArgument>(CommandValidator.validate(d, Command.SetFanSpeed(5)).errorOrNull())
        assertIs<PatchbayError.Unsupported>(CommandValidator.validate(d, Command.SetFanSpeed(2, auto = true)).errorOrNull())
    }

    @Test
    fun colourTemperatureRange() {
        val d = device(Capability.ColourTemperature(Kelvin(2000), Kelvin(9000)))
        assertTrue(CommandValidator.validate(d, Command.SetColourTemperature(Kelvin(2700))).isOk())
        assertIs<PatchbayError.InvalidArgument>(CommandValidator.validate(d, Command.SetColourTemperature(Kelvin(9500))).errorOrNull())
    }

    @Test
    fun printControlIsLimitedToDeclaredActions() {
        val d = device(Capability.PrintJob, Capability.PrintControl(setOf(PrintAction.PAUSE, PrintAction.RESUME)))
        assertTrue(CommandValidator.validate(d, Command.ControlPrint(PrintAction.PAUSE)).isOk())
        assertIs<PatchbayError.Unsupported>(CommandValidator.validate(d, Command.ControlPrint(PrintAction.STOP)).errorOrNull())
    }

    @Test
    fun missingCapabilityIsUnsupported() {
        val d = device(Capability.Power)
        assertIs<PatchbayError.Unsupported>(CommandValidator.validate(d, Command.SetVolume(3)).errorOrNull())
        assertTrue(CommandValidator.validate(d, Command.SetPower(true)).isOk())
    }

    @Test
    fun cloudOnlyAndLifeSafetyDevicesAreNeverControlled() {
        val cloud = device(Capability.Power, path = ControlPath.CLOUD_ONLY)
        assertIs<PatchbayError.Unsupported>(CommandValidator.validate(cloud, Command.SetPower(true)).errorOrNull())
        val alarm = device(Capability.Power, lifeSafety = true)
        val err = CommandValidator.validate(alarm, Command.SetPower(false)).errorOrNull()
        assertIs<PatchbayError.Unsupported>(err)
        assertTrue("life-safety" in err.message)
    }

    @Test
    fun mediaSeekNeedsPosition() {
        val d = device(Capability.MediaTransport(setOf(MediaAction.PLAY, MediaAction.SEEK)))
        assertIs<PatchbayError.InvalidArgument>(CommandValidator.validate(d, Command.Media(MediaAction.SEEK)).errorOrNull())
        assertTrue(CommandValidator.validate(d, Command.Media(MediaAction.SEEK, 1_000)).isOk())
    }
}

class UnitsTest {
    @Test
    fun fahrenheitConversion() {
        assertEquals(20.0, Celsius.fromFahrenheit(68.0).value, 1e-9)
        assertEquals(-40.0, Celsius.fromFahrenheit(-40.0).value, 1e-9)
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { Celsius(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Percent(100.1) }
        assertFailsWith<IllegalArgumentException> { Kelvin(500) }
        assertFailsWith<IllegalArgumentException> { Rgb(256, 0, 0) }
    }

    @Test
    fun roundsToStep() {
        assertEquals(21.5, Celsius(21.4).roundTo(0.5).value, 1e-9)
        assertEquals(21.0, Celsius(21.2).roundTo(0.5).value, 1e-9)
    }

    @Test
    fun outlineOfLShapeHasNoInternalSeam() {
        // 4×2 on top, 2×2 below-left: the shared edge y=2, x 0..2 must not be drawn.
        val l = Room(RoomId("l"), "L", listOf(RectM(0.0, 0.0, 4.0, 2.0), RectM(0.0, 2.0, 2.0, 2.0)))
        val segs = l.outline()
        assertTrue(segs.none { it.y1 == 2.0 && it.y2 == 2.0 && it.x1 < 2.0 }, "seam drawn: $segs")
        // The exposed part of the top rect's bottom edge (x 2..4) is drawn.
        assertTrue(segs.any { it.y1 == 2.0 && it.y2 == 2.0 && it.x1 == 2.0 && it.x2 == 4.0 })
        // Total perimeter of the L = 4 + 2 + 2 + 2 + 2 + 4 = 16.
        assertEquals(16.0, segs.sumOf { kotlin.math.abs(it.x2 - it.x1) + kotlin.math.abs(it.y2 - it.y1) }, 1e-9)
    }

    @Test
    fun outlineOfSingleRectIsItsPerimeter() {
        val r = Room(RoomId("r"), "R", listOf(RectM(1.0, 1.0, 3.0, 2.0)))
        assertEquals(4, r.outline().size)
    }

    @Test
    fun roomBoundsAndHitTest() {
        val l = Room(RoomId("l"), "L", listOf(RectM(0.0, 0.0, 4.0, 2.0), RectM(0.0, 2.0, 2.0, 2.0)))
        assertEquals(RectM(0.0, 0.0, 4.0, 4.0), l.bounds)
        val plan = FloorPlan(listOf(l))
        assertEquals(l, plan.roomAt(Placement(1.0, 3.0)))
        assertEquals(null, plan.roomAt(Placement(3.0, 3.0))) // the notch of the L
    }
}
