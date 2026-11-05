package dev.patchbay.core.model

import kotlin.jvm.JvmInline

@JvmInline
public value class DeviceId(public val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128) { "invalid device id" }
    }
}

@JvmInline
public value class AdapterId(public val value: String)

/** How a device can be controlled. Drives the Exposure Map. */
public enum class ControlPath {
    /** Works locally out of the box. */
    LOCAL,

    /** Works locally after a one-time setting (e.g. Govee "LAN Control", Bambu LAN-only mode). */
    LOCAL_AFTER_SETUP,

    /** Only reachable through a vendor cloud. Not integrated in v1: shown, never controlled. */
    CLOUD_ONLY,

    /** Seen on the network, not identified. */
    UNKNOWN,
}

public enum class DeviceKind {
    LIGHT, TV, MEDIA_RECEIVER, SPEAKER, THERMOSTAT, PRINTER_3D, AIR_PURIFIER, APPLIANCE,
    SMOKE_CO_ALARM, MICROCONTROLLER, NETWORK_INFRA, UNKNOWN,
}

/**
 * Where a device sits on the floor plan: [x]/[y] in metres from the plan origin (top-left),
 * independent of room geometry so it survives room edits.
 */
public data class Placement(val x: Double, val y: Double)

public data class Device(
    val id: DeviceId,
    val name: String,
    val kind: DeviceKind,
    val vendor: String?,
    val model: String?,
    val roomId: RoomId?,
    val placement: Placement?,
    val controlPath: ControlPath,
    val capabilities: List<Capability>,
    val adapter: AdapterId?,
    /** Firmware version, if the device exposes it locally. */
    val firmware: String? = null,
    /** True for life-safety devices; the app must never present itself as an alarm path. */
    val lifeSafety: Boolean = false,
) {
    public inline fun <reified C : Capability> capability(): C? = capabilities.firstOrNull { it is C } as C?

    public val controllable: Boolean
        get() = controlPath != ControlPath.CLOUD_ONLY && controlPath != ControlPath.UNKNOWN && !lifeSafety
}

public enum class Reachability { ONLINE, DEGRADED, OFFLINE, NEVER_CONNECTED }

/** One observation of a device's state. */
public data class DeviceSnapshot(
    val deviceId: DeviceId,
    val reachability: Reachability,
    val states: List<CapabilityState>,
    val observedAt: EpochMillis,
) {
    public inline fun <reified S : CapabilityState> state(): S? = states.firstOrNull { it is S } as S?
}
