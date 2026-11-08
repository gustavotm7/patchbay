package dev.patchbay.core.discovery

/** One address that answered the network census, and what it said about itself. All fields are untrusted. */
public data class Finding(
    val address: String,
    val hostNames: Set<String>,
    /** DNS-SD service types (e.g. "_googlecast._tcp") and SSDP search targets. */
    val services: Set<String>,
    val ports: Set<Int>,
    val friendlyName: String?,
    val model: String?,
    val server: String?,
    /** What a HomeKit (`_hap._tcp`) announcement said, when there was one. */
    val hap: HapAdvert? = null,
) {
    /** What the advertised services say about local control (for the Exposure Map). */
    val hints: List<String> get() = services.mapNotNull { ServiceHints.SERVICE_HINTS[it] ?: ServiceHints.SERVICE_HINTS.entries.firstOrNull { (k, _) -> it.startsWith(k) }?.value }.distinct()
}

/** What an advertised service implies about local control. Shown in the Exposure Map. */
public object ServiceHints {
    public val SERVICE_HINTS: Map<String, String> = mapOf(
        "_googlecast._tcp" to "Cast: local media control",
        "_androidtvremote2._tcp" to "Android TV Remote: local",
        "_hap._tcp" to "HomeKit: local control possible",
        "_esphomelib._tcp" to "ESPHome: local",
        "_matter._tcp" to "Matter: local",
        "_matterc._udp" to "Matter: commissionable",
        "_ipp._tcp" to "Printer (IPP)",
        "_printer._tcp" to "Printer (LPD)",
        "_airplay._tcp" to "AirPlay",
        "_raop._tcp" to "AirPlay audio",
        "_spotify-connect._tcp" to "Spotify Connect",
        "urn:lge-com:service:webos-second-screen" to "LG webOS TV: local control",
        "urn:bambulab-com:device:3dprinter" to "Bambu printer: local in LAN mode",
        "urn:dial-multiscreen-org" to "DIAL (TV/streamer)",
        "urn:schemas-upnp-org:device:InternetGatewayDevice" to "Router (UPnP IGD)",
        "urn:schemas-upnp-org:device:MediaRenderer" to "UPnP media renderer",
    )
}

/** The parts of a HomeKit mDNS announcement we use. Untrusted. [category] is the accessory category ("ci"; 9 = thermostat); [unpaired] is status flag bit 0 ("sf"). */
public data class HapAdvert(val port: Int, val deviceId: String?, val category: Int?, val unpaired: Boolean?)
