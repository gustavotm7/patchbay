package dev.patchbay.core.model

/**
 * Something the user (or an automation) asks a device to do. The set is closed on purpose:
 * dangerous operations (printer heating/motion/G-code, anything on a smoke/CO alarm) have no
 * representation, so no code path can send them.
 */
public sealed interface Command {
    public data class SetPower(val on: Boolean) : Command
    public data class SetBrightness(val level: Percent) : Command
    public data class SetColourTemperature(val kelvin: Kelvin) : Command
    public data class SetColour(val colour: Rgb) : Command
    public data class SetHvacMode(val mode: HvacMode) : Command

    /** Either setpoint may be null to leave it unchanged. */
    public data class SetSetpoints(val heat: Celsius?, val cool: Celsius?) : Command

    public data class SetFanSpeed(val level: Int, val auto: Boolean = false) : Command
    public data class Media(val action: MediaAction, val seekToMs: Long? = null) : Command
    public data class SetVolume(val level: Int) : Command
    public data class SetMute(val muted: Boolean) : Command
    public data class SelectInput(val source: String) : Command
    public data class ControlPrint(val action: PrintAction) : Command
    public data class PressKey(val key: RemoteKey) : Command
    public data class SetAuxLight(val lightName: String, val on: Boolean) : Command
}
