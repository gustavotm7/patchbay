package dev.patchbay.core.home

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command

/**
 * What a device's readings should show right after a command was accepted, before its own next report confirms or corrects it. Only the
 * commands that have a plain reading are shown (power, brightness, colour); a colour and a white temperature replace each other.
 */
internal fun optimisticStates(states: List<CapabilityState>, command: Command): List<CapabilityState> {
    val replacement: CapabilityState? = when (command) {
        is Command.SetPower -> CapabilityState.PowerState(command.on)
        is Command.SetBrightness -> CapabilityState.BrightnessState(command.level)
        is Command.SetColour -> CapabilityState.RgbState(command.colour)
        is Command.SetColourTemperature -> CapabilityState.ColourTemperatureState(command.kelvin)
        else -> null
    }
    if (replacement == null) return states
    val colourModes = setOf(CapabilityState.RgbState::class, CapabilityState.ColourTemperatureState::class)
    val drop = if (replacement::class in colourModes) colourModes else setOf(replacement::class)
    return states.filterNot { it::class in drop } + replacement
}
