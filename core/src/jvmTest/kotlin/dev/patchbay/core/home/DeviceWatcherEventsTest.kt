package dev.patchbay.core.home

import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.model.Reachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the watcher passes on to automations: reports from a device that presented a different key start nothing. */
class DeviceWatcherEventsTest {
    private val id = DeviceId("lamp-1")
    private val device = Device(id, "Lamp", DeviceKind.LIGHT, null, null, null, null, ControlPath.LOCAL, listOf(Capability.Power), AdapterId("test"))

    private fun snap(on: Boolean) = DeviceSnapshot(id, Reachability.ONLINE, listOf(CapabilityState.PowerState(on)), EpochMillis(0))

    @Test
    fun aDeviceWithAChangedKeyStartsNothingAndTheBaselineStaysCurrent() {
        val scope = CoroutineScope(Job())
        try {
            val devices = MutableStateFlow(listOf(LiveDevice(device, snap(false))))
            val ctx = HomeContext(scope, { 0L }, java.time.ZoneOffset.UTC, kotlin.random.Random(1), null, null, devices)
            val events = mutableListOf<DeviceEvent>()
            val watcher = DeviceWatcher(ctx, ActivityLog(ctx), Notifier(ctx), {}) { _, e -> events += e }

            fun report(on: Boolean, keyChanged: Boolean) {
                val s = snap(on)
                devices.value = listOf(LiveDevice(device, s, trustChanged = keyChanged))
                watcher.onSnapshot(s, keyChanged)
            }

            report(false, keyChanged = false) // baseline
            report(true, keyChanged = false)
            assertEquals(listOf(DeviceEvent.TURNED_ON), events)

            report(false, keyChanged = true) // the same change, from a device we no longer trust
            assertEquals(listOf(DeviceEvent.TURNED_ON), events)

            report(false, keyChanged = false) // trust is back and nothing changed since: no late event for the change we ignored
            assertEquals(listOf(DeviceEvent.TURNED_ON), events)
            report(true, keyChanged = false)
            assertEquals(listOf(DeviceEvent.TURNED_ON, DeviceEvent.TURNED_ON), events)
        } finally { scope.cancel() }
    }
}
