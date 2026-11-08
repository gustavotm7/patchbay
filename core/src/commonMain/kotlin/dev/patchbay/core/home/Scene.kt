package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId

/**
 * A saved set of light, plug and thermostat states that can be applied in one tap ("Movie night", "Away"). Scenes only ever contain
 * power, brightness, colour temperature and colour for lights and plugs, mode plus setpoints for thermostats, and volume plus mute for TVs and speakers (validated
 * against the thermostat's own ranges when run): no TV, printer, camera or lock command can be put in one, by construction
 * (safety scope, CLAUDE.md).
 */
public data class Scene(val id: String, val name: String, val devices: List<DeviceId>)
