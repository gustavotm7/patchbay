package dev.patchbay.core.model

import dev.patchbay.core.result.PatchbayResult
import dev.patchbay.core.result.invalid
import dev.patchbay.core.result.ok
import dev.patchbay.core.result.unsupported

/**
 * Checks a [Command] against a device's declared capabilities before any adapter sees it.
 * The UI and the automation engine both go through this, so an out-of-range value is rejected
 * the same way no matter where it came from.
 */
public object CommandValidator {

    public fun validate(device: Device, command: Command, current: DeviceSnapshot? = null): PatchbayResult<Unit> {
        if (device.lifeSafety) return unsupported("${device.name} is a life-safety device and is never controlled")
        if (!device.controllable) return unsupported("${device.name} has no local control path")

        return when (command) {
            is Command.SetPower -> require<Capability.Power>(device)
            is Command.SetBrightness -> {
                val cap = device.capability<Capability.Brightness>() ?: return missing(device, "brightness")
                if (command.level < cap.minimum) invalid("brightness below device minimum ${cap.minimum.value} %") else ok()
            }
            is Command.SetColourTemperature -> {
                val cap = device.capability<Capability.ColourTemperature>() ?: return missing(device, "colour temperature")
                if (command.kelvin !in cap.warmest..cap.coolest) {
                    invalid("colour temperature must be ${cap.warmest.value}–${cap.coolest.value} K")
                } else ok()
            }
            is Command.SetColour -> require<Capability.RgbColour>(device)
            is Command.SetHvacMode -> {
                val cap = device.capability<Capability.Thermostat>() ?: return missing(device, "thermostat")
                if (command.mode !in cap.modes) unsupported("mode ${command.mode} not supported") else ok()
            }
            is Command.SetSetpoints -> validateSetpoints(device, command, current)
            is Command.SetFanSpeed -> {
                val cap = device.capability<Capability.FanSpeed>() ?: return missing(device, "fan speed")
                when {
                    command.auto && !cap.hasAuto -> unsupported("fan has no auto mode")
                    command.level !in 0..cap.levels -> invalid("fan level must be 0–${cap.levels}")
                    else -> ok()
                }
            }
            is Command.Media -> {
                val cap = device.capability<Capability.MediaTransport>() ?: return missing(device, "media transport")
                when {
                    command.action !in cap.actions -> unsupported("${command.action} not supported")
                    command.action == MediaAction.SEEK && (command.seekToMs == null || command.seekToMs < 0) ->
                        invalid("seek needs a non-negative position")
                    else -> ok()
                }
            }
            is Command.SetVolume -> {
                val cap = device.capability<Capability.Volume>() ?: return missing(device, "volume")
                if (command.level !in 0..cap.maximum) invalid("volume must be 0–${cap.maximum}") else ok()
            }
            is Command.SetMute -> {
                val cap = device.capability<Capability.Volume>() ?: return missing(device, "volume")
                if (!cap.supportsMute) unsupported("mute not supported") else ok()
            }
            is Command.SelectInput -> {
                val cap = device.capability<Capability.InputSource>() ?: return missing(device, "input source")
                if (command.source !in cap.sources) invalid("unknown input source") else ok()
            }
            is Command.ControlPrint -> {
                val cap = device.capability<Capability.PrintControl>() ?: return missing(device, "print control")
                if (command.action !in cap.actions) unsupported("${command.action} not supported") else ok()
            }
            is Command.SetAuxLight -> {
                val known = device.capabilities.filterIsInstance<Capability.AuxLight>().any { it.lightName == command.lightName }
                if (!known) missing(device, "light '${command.lightName}'") else ok()
            }
        }
    }

    private fun validateSetpoints(device: Device, cmd: Command.SetSetpoints, current: DeviceSnapshot?): PatchbayResult<Unit> {
        val cap = device.capability<Capability.Thermostat>() ?: return missing(device, "thermostat")
        if (cmd.heat == null && cmd.cool == null) return invalid("no setpoint given")
        cmd.heat?.let { if (it !in cap.heatRange) return invalid("heat setpoint outside ${cap.heatRange.start.value}–${cap.heatRange.endInclusive.value} °C") }
        cmd.cool?.let { if (it !in cap.coolRange) return invalid("cool setpoint outside ${cap.coolRange.start.value}–${cap.coolRange.endInclusive.value} °C") }

        // The deadband applies between the resulting pair, so combine with the current state.
        val state = current?.state<CapabilityState.ThermostatState>()
        val heat = cmd.heat ?: state?.heatSetpoint
        val cool = cmd.cool ?: state?.coolSetpoint
        if (heat != null && cool != null && cool - heat < cap.minimumDeadband - EPSILON) {
            return invalid("heat and cool setpoints must be at least ${cap.minimumDeadband} °C apart")
        }
        return ok()
    }

    private inline fun <reified C : Capability> require(device: Device): PatchbayResult<Unit> =
        if (device.capability<C>() == null) missing(device, C::class.simpleName ?: "capability") else ok()

    private fun missing(device: Device, what: String): PatchbayResult<Nothing> =
        unsupported("${device.name} has no $what capability")

    private const val EPSILON = 1e-9
}
