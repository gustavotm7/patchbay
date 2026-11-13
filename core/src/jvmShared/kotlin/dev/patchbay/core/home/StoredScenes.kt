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
    /** Thermostat steps: an [dev.patchbay.core.model.HvacMode] name and the setpoints for it. */
    val hvacMode: String? = null,
    val heat: Double? = null,
    val cool: Double? = null,
    /** TV, streamer and speaker steps: only volume and mute are restored (never power, input, apps or playback). */
    val volume: Int? = null,
    val muted: Boolean? = null,
)

@Serializable
internal data class StoredScene(val id: String, val name: String, val steps: List<StoredStep>)

@Serializable
internal data class StoredAutomation(
    val id: String,
    val name: String,
    val sceneId: String,
    val hour: Int,
    val minute: Int,
    val days: List<Int>,
    val enabled: Boolean = true,
    val sun: String? = null,
    val offset: Int = 0,
)

@Serializable
internal data class StoredAway(val active: Boolean, val simulate: List<String>)

@Serializable
internal data class StoredLocation(val lat: Double, val lon: Double)

@Serializable
internal data class StoredTrend(val d: String, val m: String, val t: List<Long>, val v: List<Double>)
