package dev.patchbay.core.home

import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.DeviceId

/**
 * Something a device does that can start a scene. Only events Patchbay can see from what the device reports are listed, and only for devices that
 * report the thing involved: power for [TURNED_ON] and [TURNED_OFF], a print job for [PRINT_FINISHED] and [PRINT_FAILED], playback for
 * [STARTED_PLAYING] and [STOPPED_PLAYING].
 */
public enum class DeviceEvent(public val label: String) {
    TURNED_ON("turns on"),
    TURNED_OFF("turns off"),
    PRINT_FINISHED("print finishes"),
    PRINT_FAILED("print fails"),
    STARTED_PLAYING("starts playing"),
    STOPPED_PLAYING("stops playing"),
    ;

    public companion object {
        /** The events a device with these [capabilities] can produce, in the order the screen offers them. */
        public fun availableFor(capabilities: List<Capability>): List<DeviceEvent> = buildList {
            if (capabilities.any { it is Capability.Power }) { add(TURNED_ON); add(TURNED_OFF) }
            if (capabilities.any { it is Capability.PrintJob }) { add(PRINT_FINISHED); add(PRINT_FAILED) }
            if (capabilities.any { it is Capability.MediaTransport }) { add(STARTED_PLAYING); add(STOPPED_PLAYING) }
        }
    }
}

/** "When [deviceName] [event]". [deviceName] is null when the device was forgotten (the automation then does nothing). */
public data class DeviceTrigger(val device: DeviceId, val deviceName: String?, val event: DeviceEvent)
