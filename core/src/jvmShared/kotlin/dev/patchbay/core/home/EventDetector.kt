package dev.patchbay.core.home

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.PlaybackStatus
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Reachability
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns two reports from the same device into the [DeviceEvent]s that happened between them.
 *
 * Only changes seen while the device was answering count. The first report after Patchbay starts is a baseline and never an event, and a device that
 * was out of reach and comes back is compared with what it last said while reachable, so a plug that was unplugged and plugged back in does not
 * look like "turned on" unless it really changed. Buffering is not a playback change (a stream that stalls and resumes is not "started playing").
 */
internal class EventDetector {
    private data class Seen(val power: Boolean?, val stage: PrintStage?, val media: PlaybackStatus?)

    private val last = ConcurrentHashMap<DeviceId, Seen>()

    fun forget(device: DeviceId) {
        last.remove(device)
    }

    fun detect(snap: DeviceSnapshot): List<DeviceEvent> {
        if (snap.reachability != Reachability.ONLINE) return emptyList()
        val power = snap.state<CapabilityState.PowerState>()?.on
        val stage = snap.state<CapabilityState.PrintJobState>()?.stage
        val media = snap.state<CapabilityState.MediaState>()?.status
        val before = last[snap.deviceId]
        // A buffering report keeps the last steady playback state instead of replacing it.
        val steadyMedia = if (media == PlaybackStatus.BUFFERING) before?.media else media
        last[snap.deviceId] = Seen(power, stage, steadyMedia)
        if (before == null) return emptyList()
        return buildList {
            if (before.power != null && power != null && before.power != power) add(if (power) DeviceEvent.TURNED_ON else DeviceEvent.TURNED_OFF)
            if (before.stage != null && stage != null && before.stage != stage &&
                (before.stage == PrintStage.PRINTING || before.stage == PrintStage.PREPARING || before.stage == PrintStage.PAUSED)
            ) {
                if (stage == PrintStage.FINISHED) add(DeviceEvent.PRINT_FINISHED)
                if (stage == PrintStage.FAILED) add(DeviceEvent.PRINT_FAILED)
            }
            if (before.media != null && steadyMedia != null && before.media != steadyMedia) {
                if (steadyMedia == PlaybackStatus.PLAYING) add(DeviceEvent.STARTED_PLAYING)
                else if (before.media == PlaybackStatus.PLAYING) add(DeviceEvent.STOPPED_PLAYING)
            }
        }
    }
}
