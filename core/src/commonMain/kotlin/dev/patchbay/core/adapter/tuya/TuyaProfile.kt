package dev.patchbay.core.adapter.tuya

import dev.patchbay.core.model.Capability
import dev.patchbay.core.model.CapabilityState
import dev.patchbay.core.model.Command
import dev.patchbay.core.model.DeviceKind
import dev.patchbay.core.result.PatchbayError
import dev.patchbay.core.result.PatchbayResult
import kotlinx.serialization.json.JsonPrimitive

/** What a Tuya device type may be sent and read (ADR 0007). Implementations are explicit allowlists of data points. */
public interface TuyaProfile {
    public val id: String
    public val label: String
    public val kind: DeviceKind
    public val capabilities: List<Capability>

    /** True when a first reading looks like this device type (used to catch a wrong choice at pairing). */
    public fun recognises(dps: Map<String, JsonPrimitive>): Boolean

    public fun toDps(command: Command): PatchbayResult<Map<String, JsonPrimitive>>
    public fun states(dps: Map<String, JsonPrimitive>): List<CapabilityState>
}

/**
 * Read-only: connects, reads every data point the device reports and shows them, so the model's real data-point list can be
 * learned without guessing (a robot vacuum's start/pause/dock data points differ per model, and a wrong write moves a motor).
 * Nothing is ever written.
 */
public object TuyaLearnProfile : TuyaProfile {
    override val id: String = "learn"
    override val label: String = "Robot vacuum or other (read-only: shows its data points)"
    override val kind: DeviceKind = DeviceKind.APPLIANCE
    override val capabilities: List<Capability> = emptyList()
    private const val MAX_ENTRIES = 64
    private const val MAX_VALUE = 48

    override fun recognises(dps: Map<String, JsonPrimitive>): Boolean = dps.isNotEmpty()

    override fun toDps(command: Command): PatchbayResult<Map<String, JsonPrimitive>> =
        PatchbayResult.Err(PatchbayError.Unsupported("This device is in read-only mode: Patchbay shows its data points but sends it nothing."))

    override fun states(dps: Map<String, JsonPrimitive>): List<CapabilityState> {
        val entries = dps.entries
            .sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key }))
            .take(MAX_ENTRIES)
            .associate { (k, v) -> k.take(8) to v.content.filter { !it.isISOControl() }.take(MAX_VALUE) }
        return listOf(CapabilityState.DiagnosticValues("Data points", entries))
    }
}

public object TuyaProfiles {
    public val ALL: List<TuyaProfile> = TuyaBulbProfile.ALL + TuyaLearnProfile
    public fun byId(id: String): TuyaProfile? = ALL.firstOrNull { it.id == id }
    public fun byLabel(label: String?): TuyaProfile? = ALL.firstOrNull { it.label == label }
}
