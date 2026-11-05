package dev.patchbay.core.adapter

import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.log.SessionLog
import dev.patchbay.core.net.NetGuard
import dev.patchbay.core.result.PatchbayResult
import kotlinx.coroutines.CoroutineScope

/** What an adapter factory gets from Bootstrap. Adapters must use [netGuard] for every socket. */
public class AdapterContext(
    public val netGuard: NetGuard,
    public val scope: CoroutineScope,
    public val log: SessionLog,
    /** Report a user-visible problem (shown on the network screen, logged redacted). */
    public val report: (String) -> Unit,
)

/**
 * Adding a system = one factory here + an adapter + a simulator + contract tests (docs/ADDING_AN_ADAPTER.md).
 * Cloud adapters never go in [LOCAL]: they belong in a separate, opt-in quarantine module (invariant 3).
 */
public fun interface AdapterFactory {
    public fun create(ctx: AdapterContext): DeviceAdapter
}

public object AdapterRegistry {
    public val GOVEE: AdapterFactory = AdapterFactory { ctx ->
        GoveeLanAdapter(ctx.netGuard, ctx.scope).also { a ->
            (a.start() as? PatchbayResult.Err)?.let { ctx.report(it.error.message) }
        }
    }

    /** Local adapters enabled by default, in discovery order. */
    public val LOCAL: List<AdapterFactory> = listOf(GOVEE)
}
