package dev.patchbay.core.home

import dev.patchbay.core.model.DeviceId

/**
 * A saved set of light and plug states that can be applied in one tap ("Movie night"). Scenes only ever contain power,
 * brightness, colour temperature and colour for lights and plugs: no TV, printer, camera or lock command can be put in
 * one, by construction (safety scope, CLAUDE.md).
 */
public data class Scene(val id: String, val name: String, val devices: List<DeviceId>)
