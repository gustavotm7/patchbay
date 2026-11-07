package dev.patchbay.core.model

/**
 * What a device can do, described in vendor-neutral, typed terms. The UI renders controls from
 * capabilities, never from vendor names. Each capability has a matching [CapabilityState]
 * subtype and is changed only through [Command]s that [CommandValidator] accepts.
 */
public sealed interface Capability {

    public data object Power : Capability

    /** Dimming. [minimum] is the lowest non-off level the device accepts. */
    public data class Brightness(val minimum: Percent = Percent(1.0)) : Capability

    public data class ColourTemperature(val warmest: Kelvin, val coolest: Kelvin) : Capability {
        init {
            require(warmest < coolest) { "warmest must be below coolest" }
        }
    }

    /** Full RGB output. Only devices with this capability get a colour wheel. */
    public data object RgbColour : Capability

    /**
     * An HVAC thermostat. Setpoints are in °C. In [HvacMode.AUTO] the heat and cool setpoints must
     * be at least [minimumDeadband] apart (ecobee and HAP enforce this too).
     */
    public data class Thermostat(
        val modes: Set<HvacMode>,
        val heatRange: ClosedRange<Celsius>,
        val coolRange: ClosedRange<Celsius>,
        val minimumDeadband: Double,
        val step: Double = 0.5,
    ) : Capability {
        init {
            require(modes.isNotEmpty()) { "thermostat needs at least one mode" }
            require(minimumDeadband >= 0.0 && step > 0.0) { "invalid deadband/step" }
        }
    }

    public data object TemperatureSensor : Capability
    public data object HumiditySensor : Capability
    public data class OccupancySensor(val sensorName: String) : Capability

    /** Discrete fan speeds 1..[levels]; 0 means off. Rendered as detents, not a slider. */
    public data class FanSpeed(val levels: Int, val hasAuto: Boolean = false) : Capability {
        init {
            require(levels in 1..10) { "fan levels must be 1..10" }
        }
    }

    public data class MediaTransport(val actions: Set<MediaAction>) : Capability

    public data class Volume(val maximum: Int, val supportsMute: Boolean) : Capability {
        init {
            require(maximum in 1..1000) { "volume maximum out of range" }
        }
    }

    public data class InputSource(val sources: List<String>) : Capability

    /** Print job monitoring. Present on all printers. */
    public data object PrintJob : Capability

    /**
     * Print job control. Deliberately limited to pause/resume/stop (brief §4, safety scope):
     * there is no command type for heating, motion or raw G-code anywhere in the model.
     */
    public data class PrintControl(val actions: Set<PrintAction>) : Capability

    /** Read-only heater temperatures. [nozzles] is 2 on the H2D (dual nozzle). */
    public data class PrinterThermals(val nozzles: Int, val hasChamberSensor: Boolean) : Capability

    /** Filament slots (e.g. Bambu AMS units). Read-only. */
    public data class FilamentSlots(val units: Int, val slotsPerUnit: Int) : Capability

    /**
     * A local video stream (e.g. RTSP on Eufy cameras with "RTSP stream" enabled). The URL credentials are a
     * secret: stored in the encrypted DB, never logged, never shown in full.
     */
    public data class CameraStream(val protocol: StreamProtocol) : Capability

    /**
     * A door lock, **read-only** (docs/protocols/eufy.md). There's deliberately no lock/unlock command in
     * [Command]: remote unlocking is security-critical and needs an ADR plus your approval first.
     */
    public data object LockStatus : Capability

    /**
     * Remote-control keys (d-pad, home, back, volume, play/pause, power), as on a TV remote. Keys only: nothing here installs,
     * launches or reads anything.
     */
    public data object RemoteKeys : Capability

    /** A secondary light on a device, such as a printer's chamber light. */
    public data class AuxLight(val lightName: String) : Capability
}

public enum class HvacMode { OFF, HEAT, COOL, AUTO }

public enum class HvacAction { IDLE, HEATING, COOLING, FAN_ONLY }

public enum class MediaAction { PLAY, PAUSE, STOP, NEXT, PREVIOUS, SEEK }

public enum class PrintAction { PAUSE, RESUME, STOP }

/** The keys of a TV remote that Patchbay can press. */
public enum class RemoteKey { UP, DOWN, LEFT, RIGHT, SELECT, HOME, BACK, PLAY_PAUSE, VOLUME_UP, VOLUME_DOWN, MUTE, POWER }

public enum class StreamProtocol { RTSP, RTSPS }
