package dev.patchbay.core.home

import dev.patchbay.core.adapter.ManualAdd
import dev.patchbay.core.adapter.PairingPrompt
import dev.patchbay.core.adapter.StreamLease
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.net.NetGuardStats
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.StateFlow

/** A device on the LAN plus its latest observed state. */
public data class LiveDevice(
    val device: Device,
    val snapshot: DeviceSnapshot?,
    val address: String? = null,
    /** Found, but must be paired (TV prompt, TOFU fingerprint) before it can be observed or controlled. */
    val needsPairing: Boolean = false,
    /** The device presented a different security key than the one you paired. Patchbay has stopped talking to it. */
    val trustChanged: Boolean = false,
)

public enum class ScanState { IDLE, SCANNING, DONE, FAILED }

/** What one system found in the last scan, and what to try if it found nothing. */
public data class AdapterScan(val name: String, val found: Int, val note: String?, val discoverable: Boolean)

/**
 * The real home, as seen from this client: what discovery found and what each device currently reports.
 * Platform-free so the shared UI can use it; implemented by `LanHome` (jvm/android).
 */
public interface LiveHome {
    public val devices: StateFlow<List<LiveDevice>>
    public val scanState: StateFlow<ScanState>
    public val netStats: StateFlow<NetGuardStats>

    /** Everything that answered the last network census (mDNS/SSDP), including devices we don't manage. */
    public val census: StateFlow<List<Finding>>

    /** Last problem worth showing the user (redacted), e.g. "Govee needs UDP port 4002". */
    public val problem: StateFlow<String?>

    /** Per-system results of the last scan, so a missing device can be explained. Empty before the first scan. */
    public val scanReport: StateFlow<List<AdapterScan>>

    /** Clears the problem banner. */
    public fun dismissProblem()

    /** The tail of this session's log (already redacted), for the explicit "Copy log" action. */
    public fun recentLog(maxLines: Int = 300): String

    /** Systems that can be added by typing an address when discovery doesn't find them. */
    public val manualAdd: List<ManualAdd>

    /** Adds a device by LAN address and adapter-specific key, then it pairs like a scanned one. */
    public suspend fun addManually(adapter: AdapterId, address: String, key: String, choice: String? = null): PatchbayResult<DeviceId>

    /** What changed and when, newest first (bounded). Kept on this device only. */
    public val activity: StateFlow<List<LedgerEntry>>

    /** A short local-time label for an activity entry ("Today 19:30", "Yesterday 08:12", "3 Nov 08:12"). */
    public fun timeLabel(epochMillis: Long): String

    /** The activity as CSV text, for the explicit "Copy as CSV" action. */
    public fun activityCsv(): String

    /** Removes a device and its saved pairing. It reappears on the next scan, unpaired. */
    public suspend fun forget(device: DeviceId)

    /** Switches off every light and plug that is on. Returns how many were switched, or an error naming how many didn't answer. */
    public suspend fun allOff(): PatchbayResult<Int>

    /** Rooms in use, sorted. A room exists while at least one device is in it. */
    public val rooms: StateFlow<List<String>>

    /** Puts a device in a room (created on first use), or takes it out with null. Invalid names are ignored. */
    public suspend fun assignRoom(device: DeviceId, room: String?)

    public val scenes: StateFlow<List<Scene>>

    /** Saves the current state of the given lights and plugs as a scene (replacing one of the same name). */
    public suspend fun saveScene(name: String, devices: List<DeviceId>): PatchbayResult<Unit>

    public val automations: StateFlow<List<Automation>>

    /** Schedules a scene. [days] are ISO weekdays 1 to 7. Runs only while Patchbay is open or in the tray. */
    public suspend fun saveAutomation(name: String, sceneId: String, hour: Int, minute: Int, days: Set<Int>): PatchbayResult<Unit>

    public suspend fun setAutomationEnabled(id: String, enabled: Boolean)

    public suspend fun deleteAutomation(id: String)

    /** Lights whose white colour follows the day. */
    public val adaptive: StateFlow<Set<DeviceId>>

    public suspend fun setAdaptive(device: DeviceId, on: Boolean)

    public val away: StateFlow<AwayState>

    /** Turns away mode on or off. Turning it on switches everything off; [simulate] lights imitate presence in the evening. */
    public suspend fun setAway(active: Boolean, simulate: Set<DeviceId>)

    /** Applies a scene. Fails with a count if some devices didn't respond. */
    public suspend fun runScene(id: String): PatchbayResult<Unit>

    public suspend fun deleteScene(id: String)

    /** Runs discovery on every adapter and the network census. Returns the number of managed devices afterwards. */
    public suspend fun scan(): PatchbayResult<Int>

    /** Runs the adapter's pairing flow through [prompt]; on success the secret and pin are stored encrypted. */
    public suspend fun pair(device: DeviceId, prompt: PairingPrompt): PatchbayResult<Unit>

    /** A handle for playing a camera's stream, issued when the user opens it (ADR 0008). Holds credentials in memory only. */
    public suspend fun streamLease(device: DeviceId): PatchbayResult<StreamLease>

    /** Validates against the device's capabilities, then sends. */
    public suspend fun send(device: DeviceId, command: Command): PatchbayResult<Unit>
}
