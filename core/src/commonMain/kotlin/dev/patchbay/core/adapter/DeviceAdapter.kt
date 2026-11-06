package dev.patchbay.core.adapter

import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.model.DeviceSnapshot
import dev.patchbay.core.model.EpochMillis
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.flow.Flow

/**
 * The contract every device adapter implements (brief §3). Adapters get all their sockets from
 * NetGuard (Phase 1), treat everything a device sends as untrusted, and convert units to SI at
 * this boundary. Each adapter has a simulator in `:simulators` and a contract test against it.
 */
public interface DeviceAdapter {
    public val id: AdapterId

    /** True when a device must be paired (TV prompt, access code, TOFU) before [observe]/[command] work. */
    public val requiresPairing: Boolean get() = false

    /** Non-null when a device can be added by typing its address, for networks where discovery fails. */
    public val manualAdd: ManualAdd? get() = null

    /**
     * Builds the [DiscoveredDevice] for a manual add: the LAN [address] and an adapter-specific [key]
     * (a serial number, say). Validates both; opens no connection. Pairing proceeds as for a scanned device.
     */
    public fun manualDevice(address: String, key: String): PatchbayResult<DiscoveredDevice> =
        PatchbayResult.Err(PatchbayError.Unsupported("this system can't be added by address"))

    /** Finds candidate devices on the LAN. The flow completes when the scan window ends. */
    public fun discover(): Flow<DiscoveredDevice>

    /**
     * Pairs with a discovered device. Interactive steps (on-screen PIN, access code, TOFU
     * fingerprint confirmation) go through [prompt]; secrets returned in [Pairing] are stored
     * encrypted by the caller and never logged.
     */
    public suspend fun pair(device: DiscoveredDevice, prompt: PairingPrompt): PatchbayResult<Pairing>

    /** The stable [DeviceId] this adapter uses for a discovered device (adapters own their ID scheme). */
    public fun deviceId(found: DiscoveredDevice): DeviceId

    public fun capabilities(device: DeviceId): List<Capability>

    /**
     * Re-registers a device known from storage at [address], without a scan. [pairing] is what [pair]
     * returned earlier (secret + pinned fingerprint), or null for adapters that don't pair.
     */
    public fun restore(device: DeviceId, address: String, pairing: Pairing?): PatchbayResult<Unit>

    /** Hot flow of state for a paired device. Reconnects with back-off; never hammers. */
    public fun observe(device: DeviceId): Flow<DeviceSnapshot>

    /** Commands are pre-validated by [dev.patchbay.core.model.CommandValidator]. */
    public suspend fun command(device: DeviceId, command: Command): PatchbayResult<Unit>

    public suspend fun health(device: DeviceId): AdapterHealth
}

/** What discovery learned. Every field came from the network, so every field is untrusted. */
public data class DiscoveredDevice(
    val adapter: AdapterId,
    /** Stable key for this device within the adapter (MAC, serial suffix, HAP id). */
    val key: String,
    val address: String,
    val port: Int?,
    val kind: DeviceKind,
    val vendor: String?,
    val model: String?,
    val displayName: String?,
    val controlPath: ControlPath,
    val seenAt: EpochMillis,
)

public data class Pairing(
    val deviceId: DeviceId,
    /** Opaque secret material (client key, access code, LTPK…). Encrypted at rest; never logged. */
    val secret: ByteArray,
    /** SHA-256 fingerprint of the pinned certificate or key (TOFU), shown at pairing time. */
    val pinnedFingerprint: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is Pairing && other.deviceId == deviceId && other.pinnedFingerprint == pinnedFingerprint &&
            other.secret.contentEquals(secret)

    override fun hashCode(): Int = deviceId.hashCode() * 31 + (pinnedFingerprint?.hashCode() ?: 0)

    override fun toString(): String = "Pairing(deviceId=$deviceId, secret=<redacted>, pinnedFingerprint=$pinnedFingerprint)"
}

/** UI hooks an adapter can use during pairing. Implemented by the app shells. */
public interface PairingPrompt {
    /** Asks for a code shown on the device (TV PIN, HAP setup code, printer access code). Masked input. */
    public suspend fun askForCode(label: String, length: IntRange): String?

    /** Shows the certificate/key fingerprint and asks the user to trust it (TOFU). */
    public suspend fun confirmFingerprint(deviceName: String, sha256: String): Boolean

    /** Tells the user to act on the device (e.g. "Accept the prompt on the TV"). */
    public suspend fun instruct(message: String)
}

public data class AdapterHealth(
    val state: HealthState,
    val lastContact: EpochMillis?,
    val detail: String?,
)

public enum class HealthState { OK, DEGRADED, UNREACHABLE, UNPAIRED, TRUST_CHANGED }

/** What the UI asks for to add a device by address: a title and the label of the adapter-specific key field. */
public data class ManualAdd(val adapter: AdapterId, val title: String, val keyLabel: String)

/**
 * A short-lived, in-memory handle for playing a device's stream. [url] may carry credentials, so it is held only
 * while the user is watching, never logged, never persisted, never displayed. [toString] redacts it.
 */
public class StreamLease(public val url: String, public val protocol: dev.patchbay.core.model.StreamProtocol) {
    override fun toString(): String = "StreamLease(protocol=$protocol, url=<redacted>)"
}

/** Implemented by adapters whose devices can be watched (ADR 0008). */
public interface StreamProvider {
    public fun streamLease(device: DeviceId): PatchbayResult<StreamLease>
}
