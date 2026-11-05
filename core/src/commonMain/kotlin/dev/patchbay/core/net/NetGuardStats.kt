package dev.patchbay.core.net

/** Running totals shown in the UI ("NetGuard: LAN only · 0 internet connections"). */
public data class NetGuardStats(
    val allowed: Long = 0,
    val denied: Long = 0,
    val droppedInbound: Long = 0,
    val lastDenied: String? = null,
)
