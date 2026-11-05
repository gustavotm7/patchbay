package dev.patchbay.simulators

import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Celsius
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.FilamentSlot
import dev.patchbay.core.model.FloorPlan
import dev.patchbay.core.model.HeaterReading
import dev.patchbay.core.model.Home
import dev.patchbay.core.model.HvacAction
import dev.patchbay.core.model.HvacMode
import dev.patchbay.core.model.Kelvin
import dev.patchbay.core.model.MediaAction
import dev.patchbay.core.model.Metric
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.Placement
import dev.patchbay.core.model.PlaybackStatus
import dev.patchbay.core.model.PrintAction
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.model.RectM
import dev.patchbay.core.model.Rgb
import dev.patchbay.core.model.Room
import dev.patchbay.core.model.RoomId
import dev.patchbay.core.model.Trend
import dev.patchbay.core.model.TrendSource
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin

/**
 * A deterministic demo home: the data behind "demo home" mode and the screenshot tests.
 * Values mirror what the real adapters will produce (same capability and state types), so
 * screens rendered from it are faithful to what a real home will show.
 *
 * Everything is a pure function of [now], so screenshots are reproducible.
 */
public object DemoHome {

    /** 2026-01-15 19:30 UTC — fixed so renders never change between runs. */
    public val DEMO_NOW: EpochMillis = EpochMillis(1_768_505_400_000)

    public object Ids {
        public val LIVING: RoomId = RoomId("living")
        public val KITCHEN: RoomId = RoomId("kitchen")
        public val HALL: RoomId = RoomId("hall")
        public val BEDROOM: RoomId = RoomId("bedroom")
        public val WORKSHOP: RoomId = RoomId("workshop")
        public val BATH: RoomId = RoomId("bath")

        public val FLOOR_LAMP: DeviceId = DeviceId("govee-h6076-a1")
        public val SHELF_STRIP: DeviceId = DeviceId("govee-h619a-b2")
        public val BEDSIDE: DeviceId = DeviceId("govee-h6008-c3")
        public val TV: DeviceId = DeviceId("lg-webos-oled")
        public val GOOGLE_TV: DeviceId = DeviceId("cast-google-tv")
        public val KITCHEN_SPEAKER: DeviceId = DeviceId("cast-nest-mini")
        public val THERMOSTAT: DeviceId = DeviceId("ecobee-hap")
        public val PRINTER: DeviceId = DeviceId("bambu-h2d")
        public val BENCH_SENSOR: DeviceId = DeviceId("esphome-bench")
        public val PURIFIER: DeviceId = DeviceId("vesync-levoit")
        public val SMOKE_ALARM: DeviceId = DeviceId("kidde-hall")
        public val WASHER: DeviceId = DeviceId("lg-thinq-washer")
        public val UNKNOWN_ESP: DeviceId = DeviceId("unknown-192-168-1-47")
    }

    public val floorPlan: FloorPlan = FloorPlan(
        listOf(
            Room(Ids.LIVING, "Living room", listOf(RectM(0.0, 0.0, 5.8, 4.6))),
            Room(Ids.KITCHEN, "Kitchen", listOf(RectM(5.8, 0.0, 3.6, 3.4))),
            Room(Ids.HALL, "Hall", listOf(RectM(0.0, 4.6, 9.4, 1.4), RectM(5.8, 3.4, 3.6, 1.2))),
            Room(Ids.BEDROOM, "Bedroom", listOf(RectM(0.0, 6.0, 4.2, 4.0))),
            Room(Ids.BATH, "Bath", listOf(RectM(4.2, 6.0, 1.8, 2.4))),
            Room(Ids.WORKSHOP, "Workshop", listOf(RectM(6.0, 6.0, 3.4, 4.0), RectM(4.2, 8.4, 1.8, 1.6))),
        ),
    )

    private val goveeAdapter = AdapterId("govee-lan")
    private val webosAdapter = AdapterId("lg-webos")
    private val castAdapter = AdapterId("cast")
    private val hapAdapter = AdapterId("hap")
    private val bambuAdapter = AdapterId("bambu-lan")
    private val esphomeAdapter = AdapterId("esphome")

    public val thermostatCapability: Capability.Thermostat = Capability.Thermostat(
        modes = setOf(HvacMode.OFF, HvacMode.HEAT, HvacMode.COOL, HvacMode.AUTO),
        heatRange = Celsius(7.0)..Celsius(26.0),
        coolRange = Celsius(18.0)..Celsius(33.0),
        minimumDeadband = 2.0,
        step = 0.5,
    )

    public val devices: List<Device> = listOf(
        light(Ids.FLOOR_LAMP, "Floor lamp", "H6076", Ids.LIVING, Placement(0.8, 0.8), rgb = true),
        light(Ids.SHELF_STRIP, "Shelf strip", "H619A", Ids.WORKSHOP, Placement(9.0, 7.2), rgb = true),
        light(Ids.BEDSIDE, "Bedside", "H6008", Ids.BEDROOM, Placement(0.6, 6.6), rgb = false),
        Device(
            Ids.TV, "TV", DeviceKind.TV, "LG", "OLED (webOS)", Ids.LIVING, Placement(2.9, 0.4), ControlPath.LOCAL,
            listOf(
                Capability.Power,
                Capability.Volume(maximum = 100, supportsMute = true),
                Capability.InputSource(listOf("HDMI 1", "HDMI 2", "HDMI 3", "Live TV")),
                Capability.MediaTransport(setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.STOP)),
            ),
            webosAdapter,
        ),
        Device(
            Ids.GOOGLE_TV, "Google TV", DeviceKind.MEDIA_RECEIVER, "Google", "Chromecast with Google TV", Ids.LIVING,
            Placement(3.5, 0.4), ControlPath.LOCAL,
            listOf(
                Capability.MediaTransport(MediaAction.entries.toSet()),
                Capability.Volume(maximum = 100, supportsMute = true),
            ),
            castAdapter,
        ),
        Device(
            Ids.KITCHEN_SPEAKER, "Kitchen speaker", DeviceKind.SPEAKER, "Google", "Nest Mini", Ids.KITCHEN,
            Placement(8.8, 0.6), ControlPath.LOCAL,
            listOf(Capability.MediaTransport(setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.STOP)), Capability.Volume(100, true)),
            castAdapter,
        ),
        Device(
            Ids.THERMOSTAT, "Thermostat", DeviceKind.THERMOSTAT, "ecobee", "Smart Thermostat Premium", Ids.HALL,
            Placement(5.2, 5.3), ControlPath.LOCAL_AFTER_SETUP,
            listOf(
                thermostatCapability,
                Capability.TemperatureSensor,
                Capability.HumiditySensor,
                Capability.OccupancySensor("Bedroom"),
                Capability.OccupancySensor("Workshop"),
            ),
            hapAdapter,
            firmware = "4.8.7.112",
        ),
        Device(
            Ids.PRINTER, "H2D", DeviceKind.PRINTER_3D, "Bambu Lab", "H2D", Ids.WORKSHOP, Placement(7.4, 9.0),
            ControlPath.LOCAL_AFTER_SETUP,
            listOf(
                Capability.PrintJob,
                Capability.PrintControl(setOf(PrintAction.PAUSE, PrintAction.RESUME, PrintAction.STOP)),
                Capability.PrinterThermals(nozzles = 2, hasChamberSensor = true),
                Capability.FilamentSlots(units = 1, slotsPerUnit = 4),
                Capability.AuxLight("Chamber light"),
            ),
            bambuAdapter,
            firmware = "01.02.00.00",
        ),
        Device(
            Ids.BENCH_SENSOR, "Bench sensor", DeviceKind.MICROCONTROLLER, "ESPHome", "ESP32-C3", Ids.WORKSHOP,
            Placement(6.8, 7.6), ControlPath.LOCAL,
            listOf(Capability.TemperatureSensor, Capability.HumiditySensor),
            esphomeAdapter,
            firmware = "ESPHome 2026.10",
        ),
        Device(
            Ids.PURIFIER, "Air purifier", DeviceKind.AIR_PURIFIER, "Levoit", "Core 300S", Ids.BEDROOM, Placement(3.6, 9.4),
            ControlPath.CLOUD_ONLY, listOf(Capability.FanSpeed(levels = 4)), adapter = null,
        ),
        Device(
            Ids.SMOKE_ALARM, "Smoke / CO alarm", DeviceKind.SMOKE_CO_ALARM, "Kidde", "Smart Smoke + CO", Ids.HALL,
            Placement(2.2, 5.3), ControlPath.CLOUD_ONLY, emptyList(), adapter = null, lifeSafety = true,
        ),
        Device(
            Ids.WASHER, "Washer", DeviceKind.APPLIANCE, "LG", "ThinQ washer", Ids.KITCHEN, Placement(6.4, 2.8),
            ControlPath.CLOUD_ONLY, emptyList(), adapter = null,
        ),
        Device(
            Ids.UNKNOWN_ESP, "192.168.1.47", DeviceKind.UNKNOWN, "Espressif (OUI)", null, roomId = null, placement = null,
            ControlPath.UNKNOWN, emptyList(), adapter = null,
        ),
    )

    public fun home(now: EpochMillis = DEMO_NOW): Home = Home(
        name = "Demo home",
        floorPlan = floorPlan,
        devices = devices,
        snapshots = snapshots(now).associateBy { it.deviceId },
    )

    public fun snapshots(now: EpochMillis): List<DeviceSnapshot> = listOf(
        snap(Ids.FLOOR_LAMP, now, CapabilityState.PowerState(true), CapabilityState.BrightnessState(Percent(62.0)), CapabilityState.ColourTemperatureState(Kelvin(2700))),
        snap(Ids.SHELF_STRIP, now, CapabilityState.PowerState(true), CapabilityState.BrightnessState(Percent(40.0)), CapabilityState.RgbState(Rgb(0x2E, 0xC4, 0xB6))),
        snap(Ids.BEDSIDE, now, CapabilityState.PowerState(false), CapabilityState.BrightnessState(Percent(30.0)), CapabilityState.ColourTemperatureState(Kelvin(2200))),
        snap(
            Ids.TV, now,
            CapabilityState.PowerState(true), CapabilityState.VolumeState(14, muted = false), CapabilityState.InputState("HDMI 2"),
        ),
        snap(
            Ids.GOOGLE_TV, now,
            CapabilityState.MediaState(PlaybackStatus.PLAYING, "Planet Earth III", "S1 · E3 — Ocean", 1_934_000, 3_120_000),
            CapabilityState.VolumeState(100, muted = false),
        ),
        snap(
            Ids.KITCHEN_SPEAKER, now,
            CapabilityState.MediaState(PlaybackStatus.IDLE, null, null, null, null), CapabilityState.VolumeState(35, muted = false),
        ),
        snap(
            Ids.THERMOSTAT, now,
            CapabilityState.ThermostatState(HvacMode.AUTO, HvacAction.HEATING, Celsius(20.6), Celsius(21.0), Celsius(24.5)),
            CapabilityState.TemperatureReading(Celsius(20.6)),
            CapabilityState.HumidityReading(Percent(38.0)),
            CapabilityState.OccupancyReading("Bedroom", occupied = false),
            CapabilityState.OccupancyReading("Workshop", occupied = true),
        ),
        snap(
            Ids.PRINTER, now,
            CapabilityState.PrintJobState(
                stage = PrintStage.PRINTING, fileName = "enclosure_bracket_v3.3mf", progress = Percent(47.0),
                layer = 112, totalLayers = 240, remainingMinutes = 96, hms = emptyList(),
            ),
            CapabilityState.PrinterThermalsState(
                nozzles = listOf(HeaterReading(Celsius(219.0), Celsius(220.0)), HeaterReading(Celsius(38.0), null)),
                bed = HeaterReading(Celsius(65.0), Celsius(65.0)),
                chamber = HeaterReading(Celsius(34.0), null),
            ),
            CapabilityState.FilamentSlotsState(
                listOf(
                    FilamentSlot(1, 1, "PLA", Rgb(0xF2, 0xF2, 0xEE), Percent(80.0), active = false),
                    FilamentSlot(1, 2, "PETG", Rgb(0x1F, 0x1F, 0x22), Percent(55.0), active = true),
                    FilamentSlot(1, 3, "PLA", Rgb(0xE0, 0x4B, 0x2A), Percent(12.0), active = false),
                    FilamentSlot(1, 4, null, null, null, active = false),
                ),
            ),
            CapabilityState.AuxLightState("Chamber light", on = true),
        ),
        snap(Ids.BENCH_SENSOR, now, CapabilityState.TemperatureReading(Celsius(19.4)), CapabilityState.HumidityReading(Percent(44.0))),
        DeviceSnapshot(Ids.PURIFIER, Reachability.NEVER_CONNECTED, emptyList(), now),
        DeviceSnapshot(Ids.SMOKE_ALARM, Reachability.NEVER_CONNECTED, emptyList(), now),
        DeviceSnapshot(Ids.WASHER, Reachability.NEVER_CONNECTED, emptyList(), now),
        DeviceSnapshot(Ids.UNKNOWN_ESP, Reachability.ONLINE, emptyList(), now),
    )

    /** Deterministic recent history for sparklines. */
    public val trends: TrendSource = TrendSource { device, metric ->
        when (device to metric) {
            Ids.THERMOSTAT to Metric.INDOOR_TEMPERATURE ->
                series(48, DAY) { t -> 20.6 - 1.4 * sin(2 * PI * (t + 0.15)) * exp(-0.4 * t) - 0.3 * sin(9 * t) }
            Ids.THERMOSTAT to Metric.HUMIDITY -> series(48, DAY) { t -> 38.0 + 4.0 * sin(2 * PI * t + 1.1) }
            Ids.BENCH_SENSOR to Metric.INDOOR_TEMPERATURE -> series(48, DAY) { t -> 19.4 + 1.1 * sin(2 * PI * t) }
            Ids.PRINTER to Metric.NOZZLE_1 -> series(60, HOUR) { t -> 220.0 - 196.0 * exp(-t * 40.0) + 0.6 * sin(60 * t) }
            Ids.PRINTER to Metric.NOZZLE_2 -> series(60, HOUR) { t -> 38.0 + 4.0 * exp(-t * 3.0) }
            Ids.PRINTER to Metric.BED -> series(60, HOUR) { t -> 65.0 - 43.0 * exp(-t * 25.0) }
            Ids.PRINTER to Metric.CHAMBER -> series(60, HOUR) { t -> 34.0 - 10.0 * exp(-t * 4.0) }
            else -> null
        }
    }

    private const val HOUR: Long = 3_600_000
    private const val DAY: Long = 24 * HOUR

    private fun series(points: Int, span: Long, f: (Double) -> Double): Trend =
        Trend(List(points) { i -> round(f(i / (points - 1).toDouble()) * 10.0) / 10.0 }, span)

    private fun light(id: DeviceId, name: String, model: String, room: RoomId, at: Placement, rgb: Boolean): Device = Device(
        id, name, DeviceKind.LIGHT, "Govee", model, room, at, ControlPath.LOCAL_AFTER_SETUP,
        buildList {
            add(Capability.Power)
            add(Capability.Brightness())
            add(Capability.ColourTemperature(Kelvin(2000), Kelvin(9000)))
            if (rgb) add(Capability.RgbColour)
        },
        goveeAdapter,
    )

    private fun snap(id: DeviceId, now: EpochMillis, vararg states: CapabilityState): DeviceSnapshot =
        DeviceSnapshot(id, Reachability.ONLINE, states.toList(), now)
}
