package dev.patchbay.core.home

import dev.patchbay.core.adapter.DeviceAdapter
import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.Reachability
import dev.patchbay.core.net.NetGuardStats
import kotlinx.coroutines.flow.StateFlow

/** Gathers what the home knows into a [DiagnosticsInput] and builds the report behind "Copy diagnostics". Reads only; no network. */
internal class DiagnosticsCollector(
    private val ctx: HomeContext,
    private val adapters: List<DeviceAdapter>,
    private val ownerOf: (DeviceId) -> DeviceAdapter?,
    private val heal: SelfHeal,
    private val netStats: () -> NetGuardStats,
    private val scanReport: () -> List<AdapterScan>,
    private val census: () -> List<Finding>,
    private val activity: ActivityLog,
    private val scenes: StateFlow<List<Scene>>,
    private val automations: StateFlow<List<Automation>>,
    private val rooms: StateFlow<List<String>>,
    private val recentLog: (Int) -> String,
) {
    fun report(header: List<String>): String {
        val now = ctx.clock()
        val live = ctx.devices.value
        val scan = scanReport()
        val labels = HashMap<String, String>()
        val devices = live.mapIndexed { index, l ->
            val d = l.device
            val label = "#${index + 1} ${listOfNotNull(d.vendor, d.model).joinToString(" ").ifBlank { "device" }} (${d.kind.name.lowercase()})"
            labels.putIfAbsent(d.name, label)
            val snap = l.snapshot
            DeviceReport(
                label = label,
                adapterId = d.adapter?.value ?: "none",
                id = DiagnosticsReport.maskId(d.id.value),
                address = DiagnosticsReport.maskAddress(l.address),
                reachability = (snap?.reachability ?: Reachability.NEVER_CONNECTED).name.lowercase().replace('_', ' '),
                lastReadingSecondsAgo = snap?.takeIf { it.states.isNotEmpty() }?.let { (now - it.observedAt.value) / 1_000 },
                offlineForSeconds = heal.offlineForSeconds(d.id, now),
                needsPairing = l.needsPairing,
                trustChanged = l.trustChanged,
                firmware = d.firmware,
                readings = snap?.states?.map { it::class.simpleName ?: "?" }?.distinct().orEmpty(),
                lines = ownerOf(d.id)?.let { safeLines { it.diagnostics(d.id) } }.orEmpty(),
            )
        }
        val systems = adapters.map { a ->
            val found = scan.firstOrNull { it.name == a.systemName }
            SystemReport(
                name = a.systemName, adapterId = a.id.value, paired = live.count { ownerOf(it.device.id) === a },
                found = found?.takeIf { it.discoverable }?.found, scanNote = found?.note, lines = safeLines { a.adapterDiagnostics() },
            )
        }
        val offsetMinutes = ctx.zone.rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds / 60
        return DiagnosticsReport.build(
            DiagnosticsInput(
                header = header + listOf(
                    "Java ${System.getProperty("java.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
                ),
                nowMillis = now,
                utcOffsetMinutes = offsetMinutes,
                systems = systems,
                devices = devices,
                netStats = netStats(),
                census = census(),
                // "Renamed (was X)" carries a name that is no longer in use anywhere else, so only the fact is kept.
                activity = activity.entries.value.take(40).map {
                    it.copy(deviceName = labels[it.deviceName] ?: "(a device or scene)", event = if (it.event.startsWith("Renamed")) "Renamed" else it.event)
                },
                logTail = recentLog(120).lines(),
                personalNames = buildList {
                    for (l in live) {
                        val plain = listOfNotNull(l.device.vendor, l.device.model).joinToString(" ")
                        if (!l.device.name.equals(plain, ignoreCase = true)) add(l.device.name) // an auto-made "Govee H6076" is not personal
                    }
                    addAll(scenes.value.map { it.name }); addAll(automations.value.map { it.name }); addAll(rooms.value)
                },
            ),
        )
    }

    /** An adapter that fails while describing itself must not take the whole report down. */
    private fun safeLines(block: () -> List<String>): List<String> =
        try { block() } catch (e: Exception) { listOf("diagnostics unavailable: ${e.javaClass.simpleName}") }
}
