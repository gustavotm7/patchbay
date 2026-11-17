package dev.patchbay.core.discovery

/**
 * The key an adapter uses to recognise a device across scans. A stable identifier the device announced for itself beats its address,
 * because addresses change (DHCP leases, router reboots) and a device whose identity is its address comes back as a stranger:
 * a new tile, no pairing, none of its room or scenes.
 */
internal object DeviceKey {
    /** [announced] cleaned to lowercase letters and digits (at least [minLength] of them), or an address-derived fallback. */
    fun of(announced: String?, address: String, minLength: Int = 6): String {
        val clean = announced?.lowercase()?.filter { it.isLetterOrDigit() }?.take(40)?.takeIf { it.length >= minLength }
        return clean ?: byAddress(address)
    }

    fun byAddress(address: String): String = "ip-" + address.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
}
