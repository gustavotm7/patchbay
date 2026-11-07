package dev.patchbay.core.home

import kotlinx.serialization.Serializable

/** How scenes are saved (as one encrypted setting). Only the values a scene may contain; see [Scene]. */
@Serializable
internal data class StoredStep(
    val device: String,
    val power: Boolean,
    val brightness: Double? = null,
    val kelvin: Int? = null,
    val rgb: List<Int>? = null,
)

@Serializable
internal data class StoredScene(val id: String, val name: String, val steps: List<StoredStep>)
