package dev.patchbay.core.home

import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.model.ControlPath

/**
 * The Exposure Map as data and as an exportable text report (brief §5): for every device, how it can be
 * controlled, what the census saw, and suggested router rules. Rules are text only, never applied.
 */
public data class ExposureReport(val entries: List<Entry>, val generatedAt: String) {

    public data class Entry(
        val name: String,
        val address: String?,
        val path: ControlPath,
        val detail: List<String>,
        val managed: Boolean,
    )

    public val local: List<Entry> get() = entries.filter { it.path == ControlPath.LOCAL || it.path == ControlPath.LOCAL_AFTER_SETUP }
    public val cloudOnly: List<Entry> get() = entries.filter { it.path == ControlPath.CLOUD_ONLY }
    public val unknown: List<Entry> get() = entries.filter { it.path == ControlPath.UNKNOWN }

    /** Plain-text report (Markdown). Contains IPs and device names, no secrets. Exported only on request. */
    public fun text(): String = buildString {
        appendLine("# Patchbay exposure report")
        appendLine()
        appendLine("Generated $generatedAt. Local network only; nothing in this report was sent anywhere.")
        appendLine()
        section("Controlled locally by Patchbay", entries.filter { it.managed })
        section("Local control possible (not managed yet)", local.filter { !it.managed })
        section("Cloud-only (shown, never contacted)", cloudOnly)
        section("Answered but not identified", unknown)
        appendLine("## Suggested router rules (not applied)")
        appendLine()
        val blockable = entries.filter { it.managed && it.address != null }
        if (blockable.isEmpty()) {
            appendLine("No managed devices with a known address yet.")
        } else {
            appendLine("These devices are controlled fully over your LAN by Patchbay, so blocking their internet access")
            appendLine("shouldn't affect Patchbay. Vendor apps, voice assistants and firmware updates **will** stop working.")
            appendLine("Give each device a DHCP reservation first so its address doesn't change.")
            appendLine()
            appendLine("OpenWrt (`/etc/config/firewall`):")
            appendLine()
            appendLine("```")
            for (e in blockable) {
                appendLine("config rule")
                appendLine("\toption name 'patchbay-block-${slug(e.name)}'")
                appendLine("\toption src 'lan'")
                appendLine("\toption src_ip '${e.address}'")
                appendLine("\toption dest 'wan'")
                appendLine("\toption target 'REJECT'")
                appendLine()
            }
            appendLine("```")
            appendLine()
            appendLine("Generic: block outbound WAN traffic from " + blockable.joinToString(", ") { "${it.address} (${it.name})" } + ".")
        }
    }

    private fun StringBuilder.section(title: String, list: List<Entry>) {
        appendLine("## $title (${list.size})")
        appendLine()
        if (list.isEmpty()) appendLine("None.")
        for (e in list) {
            append("- **${e.name}**")
            e.address?.let { append(" `$it`") }
            if (e.detail.isNotEmpty()) append(": " + e.detail.joinToString("; "))
            appendLine()
        }
        appendLine()
    }

    public companion object {
        private fun slug(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').take(32)

        /**
         * Merges what Patchbay manages with what the census heard. Census entries at the address of a
         * managed device are folded into that device rather than listed twice.
         */
        public fun build(
            managed: List<LiveDevice>,
            census: List<Finding>,
            known: List<dev.patchbay.core.model.Device> = emptyList(),
            generatedAt: String,
        ): ExposureReport {
            val entries = ArrayList<Entry>()
            val managedAddresses = HashSet<String>()
            for (d in managed) {
                val addr = d.address
                addr?.let(managedAddresses::add)
                val extra = census.firstOrNull { it.address == addr }?.hints.orEmpty()
                entries += Entry(d.device.name, addr, d.device.controlPath, listOfNotNull(d.device.model) + extra, managed = true)
            }
            for (k in known) {
                if (entries.any { it.name == k.name }) continue
                entries += Entry(k.name, null, k.controlPath, listOfNotNull(k.vendor, k.model), managed = false)
            }
            for (f in census) {
                if (f.address in managedAddresses) continue
                val hints = f.hints
                val path = if (hints.any { "local" in it.lowercase() }) ControlPath.LOCAL_AFTER_SETUP else ControlPath.UNKNOWN
                val name = f.friendlyName ?: f.model ?: f.hostNames.firstOrNull() ?: f.address
                entries += Entry(name, f.address, path, hints.ifEmpty { f.services.take(3).toList() } + listOfNotNull(f.server), managed = false)
            }
            return ExposureReport(entries, generatedAt)
        }
    }
}
