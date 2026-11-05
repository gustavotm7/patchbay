package dev.patchbay.core.model

/** Observed state for one [Capability]. Produced only by adapters (or simulators). */
public sealed interface CapabilityState {

    public data class PowerState(val on: Boolean) : CapabilityState

    public data class BrightnessState(val level: Percent) : CapabilityState

    public data class ColourTemperatureState(val kelvin: Kelvin) : CapabilityState

    public data class RgbState(val colour: Rgb) : CapabilityState

    public data class ThermostatState(
        val mode: HvacMode,
        val action: HvacAction,
        val current: Celsius,
        val heatSetpoint: Celsius?,
        val coolSetpoint: Celsius?,
    ) : CapabilityState

    public data class TemperatureReading(val temperature: Celsius) : CapabilityState

    public data class HumidityReading(val relative: Percent) : CapabilityState

    public data class OccupancyReading(val sensorName: String, val occupied: Boolean) : CapabilityState

    /** 0 = off; 1..levels. [auto] is true when the device chooses the level itself. */
    public data class FanSpeedState(val level: Int, val auto: Boolean = false) : CapabilityState

    public data class MediaState(
        val status: PlaybackStatus,
        val title: String?,
        val subtitle: String?,
        val positionMs: Long?,
        val durationMs: Long?,
    ) : CapabilityState

    public data class VolumeState(val level: Int, val muted: Boolean) : CapabilityState

    public data class InputState(val current: String) : CapabilityState

    public data class PrintJobState(
        val stage: PrintStage,
        val fileName: String?,
        val progress: Percent,
        val layer: Int,
        val totalLayers: Int,
        val remainingMinutes: Int?,
        val hms: List<HmsEvent>,
    ) : CapabilityState {
        init {
            require(layer >= 0 && totalLayers >= 0 && layer <= totalLayers) { "invalid layer counts" }
        }
    }

    public data class PrinterThermalsState(
        val nozzles: List<HeaterReading>,
        val bed: HeaterReading,
        val chamber: HeaterReading?,
    ) : CapabilityState

    public data class FilamentSlotsState(val slots: List<FilamentSlot>) : CapabilityState

    public data class AuxLightState(val lightName: String, val on: Boolean) : CapabilityState

    public data class LockState(val state: LockPosition, val battery: Percent?) : CapabilityState

    /** Whether a local stream is reachable right now. The URL is never part of state. */
    public data class StreamState(val available: Boolean, val resolution: String?) : CapabilityState
}

public enum class LockPosition { LOCKED, UNLOCKED, JAMMED, UNKNOWN }

public enum class PlaybackStatus { PLAYING, PAUSED, BUFFERING, IDLE }

public enum class PrintStage { IDLE, PREPARING, PRINTING, PAUSED, FINISHED, FAILED }

/** A heater's measured temperature and, if set, its target. Read-only in v1. */
public data class HeaterReading(val current: Celsius, val target: Celsius?)

public data class FilamentSlot(
    val unit: Int,
    val index: Int,
    val material: String?,
    val colour: Rgb?,
    val remaining: Percent?,
    val active: Boolean,
)

/** A Bambu HMS (health management) event: [code] is the vendor code, shown verbatim. */
public data class HmsEvent(val code: String, val severity: Severity, val summary: String)

public enum class Severity { INFO, WARNING, FAULT }
