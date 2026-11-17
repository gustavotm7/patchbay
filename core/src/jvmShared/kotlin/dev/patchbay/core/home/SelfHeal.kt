package dev.patchbay.core.home

import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.adapter.DiscoveredDevice
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Reachability
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-healing: a device that has been offline for a while may simply have moved (a new address after a DHCP change or a router reboot), so
 * look for it again without the user pressing Scan. Only the adapters that have such a device are searched, in the background (the Scan
 * button and the report are untouched), never while a scan is running, and with a back-off per adapter (2 minutes, doubling to 30) that
 * resets when one of its devices answers. Adapters that can't discover (cameras and Tuya devices are added by address) are skipped.
 */
internal class SelfHeal(
    private val ctx: HomeContext,
    private val adapters: List<DeviceAdapter>,
    private val ownerOf: (DeviceId) -> DeviceAdapter?,
    private val scanning: () -> Boolean,
    /** Registers what a search found, under the home's lock. */
    private val add: (DeviceAdapter, DiscoveredDevice) -> Unit,
) {
    /** When each device was first seen offline in its current stretch (cleared when it answers again). */
    private val offlineSince = ConcurrentHashMap<DeviceId, Long>()
    private class HealState { @Volatile var nextAt: Long = 0; @Volatile var backoffMs: Long = HEAL_FIRST_BACKOFF_MS }
    private val states = ConcurrentHashMap<AdapterId, HealState>()
    private val lock = Mutex()

    fun noteReachability(device: DeviceId, reachability: Reachability) {
        when (reachability) {
            Reachability.OFFLINE -> offlineSince.putIfAbsent(device, ctx.clock())
            Reachability.ONLINE -> { offlineSince.remove(device); ownerOf(device)?.id?.let { states.remove(it) } }
            else -> {}
        }
    }

    fun forget(device: DeviceId) {
        offlineSince.remove(device)
    }

    /** How long a device has been offline, for the diagnostics report. */
    fun offlineForSeconds(device: DeviceId, nowMillis: Long): Long? = offlineSince[device]?.let { (nowMillis - it) / 1_000 }

    suspend fun tick(nowMillis: Long) {
        if (scanning()) return
        val due = adapters.filter { a ->
            a.discoverable && ctx.devices.value.any { d -> ownerOf(d.device.id) === a && offlineSince[d.device.id]?.let { nowMillis - it >= HEAL_AFTER_MS } == true }
        }
        for (a in due) {
            val st = states.getOrPut(a.id) { HealState() }
            if (nowMillis < st.nextAt) continue
            if (!lock.tryLock()) return
            try {
                st.nextAt = nowMillis + st.backoffMs
                st.backoffMs = (st.backoffMs * 2).coerceAtMost(HEAL_MAX_BACKOFF_MS)
                ctx.log?.info("home", "${a.systemName}: looking again for devices that stopped answering")
                a.discover().collect { found -> add(a, found) }
            } finally {
                lock.unlock()
            }
        }
    }

    private companion object {
        /** How long a device must have been offline before a quiet rescan looks for it, the first pause between rescans per adapter, and the longest. */
        const val HEAL_AFTER_MS = 90_000L
        const val HEAL_FIRST_BACKOFF_MS = 120_000L
        const val HEAL_MAX_BACKOFF_MS = 30 * 60_000L
    }
}
