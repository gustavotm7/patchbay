package dev.patchbay.core.home

import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Percent
import dev.patchbay.core.model.PlaybackStatus
import dev.patchbay.core.model.PrintStage
import dev.patchbay.core.model.Reachability
import kotlin.test.Test
import kotlin.test.assertEquals

/** What counts as "a device did something": only changes seen while it answered, never the first report, never a stalled stream. */
class EventDetectorTest {
    private val id = DeviceId("lamp-1")

    private fun snap(
        reach: Reachability = Reachability.ONLINE, power: Boolean? = null, stage: PrintStage? = null, media: PlaybackStatus? = null,
    ): DeviceSnapshot {
        val states = buildList<CapabilityState> {
            power?.let { add(CapabilityState.PowerState(it)) }
            stage?.let { add(CapabilityState.PrintJobState(it, null, Percent(0.0), 0, 0, null, emptyList())) }
            media?.let { add(CapabilityState.MediaState(it, null, null, null, null)) }
        }
        return DeviceSnapshot(id, reach, states, EpochMillis(0))
    }

    @Test
    fun theFirstReportIsABaselineAndNeverAnEvent() {
        val d = EventDetector()
        assertEquals(emptyList(), d.detect(snap(power = true)))
        assertEquals(emptyList(), d.detect(snap(power = true)))
    }

    @Test
    fun powerChangesAreEventsInBothDirections() {
        val d = EventDetector()
        d.detect(snap(power = false))
        assertEquals(listOf(DeviceEvent.TURNED_ON), d.detect(snap(power = true)))
        assertEquals(emptyList(), d.detect(snap(power = true)))
        assertEquals(listOf(DeviceEvent.TURNED_OFF), d.detect(snap(power = false)))
    }

    @Test
    fun aDeviceThatWasOutOfReachIsComparedWithWhatItLastSaidWhileReachable() {
        val d = EventDetector()
        d.detect(snap(power = true))
        assertEquals(emptyList(), d.detect(snap(Reachability.OFFLINE, power = false))) // stale or empty values while offline are not trusted
        assertEquals(emptyList(), d.detect(snap(Reachability.DEGRADED, power = false)))
        assertEquals(emptyList(), d.detect(snap(power = true))) // plugged back in, still on: not "turned on"
        d.detect(snap(Reachability.OFFLINE))
        assertEquals(listOf(DeviceEvent.TURNED_OFF), d.detect(snap(power = false))) // really changed while we couldn't see it
    }

    @Test
    fun aPrintFinishingOrFailingIsAnEventOnlyAfterItWasRunning() {
        val d = EventDetector()
        d.detect(snap(stage = PrintStage.IDLE))
        assertEquals(emptyList(), d.detect(snap(stage = PrintStage.PREPARING)))
        assertEquals(emptyList(), d.detect(snap(stage = PrintStage.PRINTING)))
        assertEquals(listOf(DeviceEvent.PRINT_FINISHED), d.detect(snap(stage = PrintStage.FINISHED)))
        assertEquals(emptyList(), d.detect(snap(stage = PrintStage.IDLE)))
        assertEquals(emptyList(), d.detect(snap(stage = PrintStage.FINISHED))) // a printer that jumps from idle to "finished" is not a job we watched
        d.detect(snap(stage = PrintStage.PRINTING))
        d.detect(snap(stage = PrintStage.PAUSED))
        assertEquals(listOf(DeviceEvent.PRINT_FAILED), d.detect(snap(stage = PrintStage.FAILED)))
    }

    @Test
    fun aPrinterAlreadyFinishedWhenPatchbayStartsIsNotAnEvent() {
        val d = EventDetector()
        assertEquals(emptyList(), d.detect(snap(stage = PrintStage.FINISHED)))
        assertEquals(emptyList(), d.detect(snap(stage = PrintStage.FINISHED)))
    }

    @Test
    fun bufferingIsNotAPlaybackChange() {
        val d = EventDetector()
        d.detect(snap(media = PlaybackStatus.IDLE))
        assertEquals(emptyList(), d.detect(snap(media = PlaybackStatus.BUFFERING)))
        assertEquals(listOf(DeviceEvent.STARTED_PLAYING), d.detect(snap(media = PlaybackStatus.PLAYING)))
        assertEquals(emptyList(), d.detect(snap(media = PlaybackStatus.BUFFERING))) // the stream stalls...
        assertEquals(emptyList(), d.detect(snap(media = PlaybackStatus.PLAYING))) // ...and resumes: nothing happened
        assertEquals(listOf(DeviceEvent.STOPPED_PLAYING), d.detect(snap(media = PlaybackStatus.PAUSED)))
        assertEquals(emptyList(), d.detect(snap(media = PlaybackStatus.IDLE))) // paused to idle: it was already not playing
        assertEquals(listOf(DeviceEvent.STARTED_PLAYING), d.detect(snap(media = PlaybackStatus.PLAYING)))
        assertEquals(listOf(DeviceEvent.STOPPED_PLAYING), d.detect(snap(media = PlaybackStatus.IDLE)))
    }

    @Test
    fun forgettingADeviceStartsItsBaselineOver() {
        val d = EventDetector()
        d.detect(snap(power = false))
        d.forget(id)
        assertEquals(emptyList(), d.detect(snap(power = true)))
    }

    @Test
    fun eventsAreOfferedOnlyForWhatADeviceReports() {
        use(listOf(dev.patchbay.core.model.Capability.Power), listOf(DeviceEvent.TURNED_ON, DeviceEvent.TURNED_OFF))
        use(listOf(dev.patchbay.core.model.Capability.PrintJob), listOf(DeviceEvent.PRINT_FINISHED, DeviceEvent.PRINT_FAILED))
        use(listOf(dev.patchbay.core.model.Capability.TemperatureSensor), emptyList())
    }

    private fun use(caps: List<dev.patchbay.core.model.Capability>, expected: List<DeviceEvent>) {
        assertEquals(expected, DeviceEvent.availableFor(caps))
    }
}
