package dev.patchbay.core.home

import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.log.Redactor
import dev.patchbay.core.net.NetGuardStats

/** One system (adapter) as the report shows it. [lines] are the adapter's own diagnostics: shapes, counters, the last few things it could not handle. */
public data class SystemReport(val name: String, val adapterId: String, val paired: Int, val found: Int?, val scanNote: String?, val lines: List<String>)

/**
 * One device as the report shows it: no name of the owner's choosing, no values, no full address or identifier. [label] is numbered by the
 * caller ("#3 Govee H6076 (light)") and is what the activity list refers to. [readings] are the *kinds* of reading it reports (e.g. "PowerState"), not the readings.
 */
public data class DeviceReport(
    val label: String,
    val adapterId: String,
    val id: String,
    val address: String?,
    val reachability: String,
    val lastReadingSecondsAgo: Long?,
    val offlineForSeconds: Long?,
    val needsPairing: Boolean,
    val trustChanged: Boolean,
    val firmware: String?,
    val readings: List<String>,
    val lines: List<String>,
)

public data class DiagnosticsInput(
    /** Lines from the app shell: version, platform. */
    val header: List<String>,
    val nowMillis: Long,
    val utcOffsetMinutes: Int,
    val systems: List<SystemReport>,
    val devices: List<DeviceReport>,
    val netStats: NetGuardStats,
    val census: List<Finding>,
    /** Newest first. [LedgerEntry.deviceName] has already been replaced by a device label. */
    val activity: List<LedgerEntry>,
    val logTail: List<String>,
    /**
     * Names the owner chose (devices they renamed, rooms, scenes, automations). They are replaced by "(name)" wherever free text could carry them
     * (the log, the activity list, what adapters say), because the log and the ledger are written for the owner and mention them.
     */
    val personalNames: List<String> = emptyList(),
)

/**
 * Builds the text behind **Copy diagnostics**: what a person needs to send so a failing device can be understood without a second round of
 * testing. It is redacted by construction (shapes, counters, masked identifiers, never secrets, names or readings), then passed through
 * [Redactor] as a second line of defence, and bounded in size. Built on this device; nothing is sent anywhere.
 */
public object DiagnosticsReport {
    public const val MAX_CHARS: Int = 30_000
    private const val MAX_LINE = 320
    private const val MAX_SYSTEMS = 24
    private const val MAX_DEVICES = 60
    private const val MAX_SERVICES = 30
    private const val MAX_ACTIVITY = 25
    private const val MAX_LOG_LINES = 60

    private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val ipv4Anywhere = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b")

    /** `192.168.1.23` becomes `192.168.*.23`: enough to see which network and tell devices apart, not enough to locate a home. */
    public fun maskAddress(address: String?): String? {
        if (address == null) return null
        ipv4.matchEntire(address.trim())?.let { m -> return "${m.groupValues[1]}.${m.groupValues[2]}.*.${m.groupValues[4]}" }
        if (':' in address) {
            val groups = address.split(':').filter { it.isNotEmpty() }
            return if (groups.size >= 2) "${groups.first().take(4)}:…:${groups.last().takeLast(4)}" else "…"
        }
        return "(name)"
    }

    /** `tuya-bf1234567890abcdef12` becomes `tuya-…ef12`: the kind of id and enough of the end to tell two devices apart. */
    public fun maskId(id: String): String {
        val dash = id.indexOf('-')
        val head = if (dash in 1..12) id.substring(0, dash + 1) else ""
        return head + "…" + id.takeLast(4)
    }

    /** Any address that slipped into free text is masked the same way as the ones listed in the device section. */
    private fun maskAddresses(s: String): String = ipv4Anywhere.replace(s) { m -> "${m.groupValues[1]}.${m.groupValues[2]}.*.${m.groupValues[4]}" }

    public fun ago(seconds: Long): String = when {
        seconds < 0 -> "just now"
        seconds < 90 -> "$seconds s"
        seconds < 90 * 60 -> "${seconds / 60} min"
        else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
    }

    public fun build(i: DiagnosticsInput): String {
        val out = StringBuilder()
        val names = i.personalNames.map { it.trim() }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }
            .map { Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])") }
        /** Free text (the log, the activity list, what an adapter says) may mention what the owner called things, or an address. */
        fun personal(s: String): String = maskAddresses(names.fold(s) { acc, re -> re.replace(acc, "(name)") })
        fun line(s: String = "") {
            out.append(s.filter { !it.isISOControl() }.take(MAX_LINE)).append('\n')
        }
        fun section(title: String) {
            line(); line("== $title ==")
        }

        line("Patchbay diagnostics")
        line("Built on this device at ${java.time.Instant.ofEpochMilli(i.nowMillis)} (UTC${offset(i.utcOffsetMinutes)}). Nothing was sent anywhere; read it before you share it.")
        i.header.forEach(::line)

        section("Systems")
        if (i.systems.isEmpty()) line("none")
        for (s in i.systems.take(MAX_SYSTEMS)) {
            val found = s.found?.let { "; found in the last scan: $it" } ?: ""
            // What to try is only interesting when the scan found nothing.
            val note = if (s.found == 0) s.scanNote?.let { " - $it" } ?: "" else ""
            line("${s.name} (${s.adapterId}): ${s.paired} added$found$note")
            s.lines.forEach { line("  ${personal(it)}") }
        }

        section("Devices")
        if (i.devices.isEmpty()) line("none")
        for (d in i.devices.take(MAX_DEVICES)) {
            line("${d.label} - ${d.adapterId} - id ${d.id}${d.address?.let { " - address $it" } ?: ""}")
            val last = d.lastReadingSecondsAgo?.let { "last reading ${ago(it)} ago" } ?: "no reading yet"
            val off = d.offlineForSeconds?.let { "; offline for ${ago(it)}" } ?: ""
            line("  ${d.reachability}; $last$off; needs pairing: ${d.needsPairing}; security key changed: ${d.trustChanged}${d.firmware?.let { "; firmware ${it.take(40)}" } ?: ""}")
            if (d.readings.isNotEmpty()) line("  kinds of reading: ${d.readings.joinToString(", ")}")
            d.lines.forEach { line("  ${personal(it)}") }
        }
        if (i.devices.size > MAX_DEVICES) line("(${i.devices.size - MAX_DEVICES} more devices not listed)")

        section("Network")
        val n = i.netStats
        line("NetGuard: allowed ${n.allowed}, refused ${n.denied}, inbound dropped ${n.droppedInbound}${n.lastDenied?.let { ", last refused: ${it.take(80)}" } ?: ""}")
        val byService = i.census.flatMap { it.services }.groupingBy { it.filter { c -> c.code in 32..126 }.take(48) }.eachCount()
        line("Census: ${i.census.size} address(es) answered")
        byService.entries.sortedByDescending { it.value }.take(MAX_SERVICES).forEach { (service, count) -> line("  $service x$count") }

        section("Recent activity (newest first)")
        if (i.activity.isEmpty()) line("none")
        for (e in i.activity.take(MAX_ACTIVITY)) line(personal("${ago((i.nowMillis - e.atMillis) / 1_000)} ago - ${e.deviceName.take(60)}: ${e.event.take(100)}"))

        section("Log tail (already redacted)")
        if (i.logTail.isEmpty()) line("empty")
        i.logTail.takeLast(MAX_LOG_LINES).forEach { line(personal(it)) }

        val redacted = Redactor.redact(out.toString())
        return if (redacted.length <= MAX_CHARS) redacted else redacted.take(MAX_CHARS).substringBeforeLast('\n') + "\n(report cut at $MAX_CHARS characters)\n"
    }

    private fun offset(minutes: Int): String {
        val sign = if (minutes < 0) "-" else "+"
        val m = kotlin.math.abs(minutes)
        return "%s%02d:%02d".format(sign, m / 60, m % 60)
    }
}
