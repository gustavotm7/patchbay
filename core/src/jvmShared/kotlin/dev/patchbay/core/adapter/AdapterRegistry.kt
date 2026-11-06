package dev.patchbay.core.adapter

import dev.patchbay.core.adapter.bambu.BambuAdapter
import dev.patchbay.core.adapter.govee.GoveeLanAdapter
import dev.patchbay.core.adapter.kasa.KasaAdapter
import dev.patchbay.core.adapter.rtsp.RtspCameraAdapter
import dev.patchbay.core.adapter.tuya.TuyaLocalAdapter
import dev.patchbay.core.adapter.webos.WebOsAdapter
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

    /** LG webOS TVs: pinned TLS on 3001 only; needs pairing (fingerprint + on-TV prompt). */
    public val LG_WEBOS: AdapterFactory = AdapterFactory { ctx -> WebOsAdapter(ctx.netGuard) }

    /** Bambu Lab printers: pinned MQTT/TLS on 8883 + LAN access code; monitor, pause/resume/stop, light. */
    public val BAMBU: AdapterFactory = AdapterFactory { ctx -> BambuAdapter(ctx.netGuard) }

    /** TP-Link Kasa plugs and switches on the legacy local protocol (TCP/UDP 9999). KLAP-only firmware is not found. */
    public val KASA: AdapterFactory = AdapterFactory { ctx -> KasaAdapter(ctx.netGuard, ctx.scope) }

    /** RTSP cameras (eufy with RTSP switched on): added by address, view-only, status check. */
    public val RTSP_CAMERA: AdapterFactory = AdapterFactory { ctx -> RtspCameraAdapter(ctx.netGuard) }

    /** Tuya bulbs (Lepro and others) on local protocol 3.3, added by address with a user-supplied local key (ADR 0007). */
    public val TUYA_LOCAL: AdapterFactory = AdapterFactory { ctx -> TuyaLocalAdapter(ctx.netGuard) }

    /** Local adapters enabled by default, in discovery order. */
    public val LOCAL: List<AdapterFactory> = listOf(GOVEE, LG_WEBOS, BAMBU, KASA, RTSP_CAMERA, TUYA_LOCAL)
}
