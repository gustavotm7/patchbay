package dev.patchbay.core.home

/**
 * Run a [Scene] at a time of day on chosen days. [days] are ISO weekdays (1 = Monday ... 7 = Sunday). Runs only while Patchbay is
 * open or running in the tray: nothing is scheduled with the operating system, and a time that passes while it isn't running
 * is not caught up later. [sceneName] is null when the scene was deleted (the automation then does nothing).
 */
public data class Automation(
    val id: String,
    val name: String,
    val sceneId: String,
    val sceneName: String?,
    val hour: Int,
    val minute: Int,
    val days: Set<Int>,
    val enabled: Boolean,
)
