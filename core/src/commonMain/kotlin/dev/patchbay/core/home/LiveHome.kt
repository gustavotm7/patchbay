package dev.patchbay.core.home

import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.net.NetGuardStats
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.StateFlow

/** A device on the LAN plus its latest observed state. */
public data class LiveDevice(val device: Device, val snapshot: DeviceSnapshot?)

public enum class ScanState { IDLE, SCANNING, DONE, FAILED }

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

    /** Runs discovery on every adapter and the network census. Returns the number of managed devices afterwards. */
    public suspend fun scan(): PatchbayResult<Int>

    /** Validates against the device's capabilities, then sends. */
    public suspend fun send(device: DeviceId, command: Command): PatchbayResult<Unit>
}
