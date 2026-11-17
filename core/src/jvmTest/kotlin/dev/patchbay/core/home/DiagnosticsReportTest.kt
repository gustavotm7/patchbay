package dev.patchbay.core.home

import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.net.NetGuardStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsReportTest {
    private val now = 1_800_000_000_000L

    private fun device(n: Int, lines: List<String> = emptyList(), address: String? = "192.168.1.23") = DeviceReport(
        label = "#$n Govee H6076 (light)", adapterId = "govee-lan", id = DiagnosticsReport.maskId("govee-1F80C5323236724E"), address = DiagnosticsReport.maskAddress(address),
        reachability = "online", lastReadingSecondsAgo = 4, offlineForSeconds = null, needsPairing = false, trustChanged = false, firmware = "1.00.18",
        readings = listOf("PowerState", "BrightnessState"), lines = lines,
    )

    private fun input(
        devices: List<DeviceReport> = listOf(device(1)),
        systems: List<SystemReport> = listOf(SystemReport("Govee lights", "govee-lan", 1, 1, null, listOf("messages rejected as out of spec: 0"))),
        activity: List<LedgerEntry> = emptyList(),
        log: List<String> = emptyList(),
        census: List<Finding> = emptyList(),
    ) = DiagnosticsInput(listOf("Patchbay 0.1.0"), now, 60, systems, devices, NetGuardStats(allowed = 120, denied = 0), census, activity, log)

    @Test
    fun masksAddressesAndIdentifiers() {
        assertEquals("192.168.*.23", DiagnosticsReport.maskAddress("192.168.1.23"))
        assertEquals("fe80:…:7a8b", DiagnosticsReport.maskAddress("fe80::1a2b:3c4d:5e6f:7a8b"))
        assertEquals("(name)", DiagnosticsReport.maskAddress("camera.local"))
        assertNull(DiagnosticsReport.maskAddress(null))
        assertEquals("tuya-…ef12", DiagnosticsReport.maskId("tuya-bf1234567890abcdef12"))
        assertEquals("govee-…724E", DiagnosticsReport.maskId("govee-1F80C5323236724E"))
        assertEquals("…cdef", DiagnosticsReport.maskId("0123456789abcdef"))
    }

    @Test
    fun agesAreReadable() {
        assertEquals("12 s", DiagnosticsReport.ago(12))
        assertEquals("3 min", DiagnosticsReport.ago(180))
        assertEquals("2 h 5 min", DiagnosticsReport.ago(2 * 3_600 + 5 * 60))
        assertEquals("just now", DiagnosticsReport.ago(-3))
    }

    @Test
    fun hasEverySectionAndTheFactsInThem() {
        val text = DiagnosticsReport.build(
            input(
                devices = listOf(device(1, listOf("pair-setup method used in this session: 0"))),
                activity = listOf(LedgerEntry(now - 90_000, "#1 Govee H6076 (light)", "went offline")),
                log = listOf("2026-11-17T20:41:03.123+01:00 INFO  home: scan found 3"),
                census = listOf(Finding("192.168.1.40", emptySet(), setOf("_googlecast._tcp", "_hap._tcp"), emptySet(), "Hallway", null, null)),
            ),
        )
        for (part in listOf("Patchbay diagnostics", "Patchbay 0.1.0", "== Systems ==", "== Devices ==", "== Network ==", "== Recent activity (newest first) ==", "== Log tail (already redacted) ==")) {
            assertTrue(part in text, "missing: $part\n$text")
        }
        assertTrue("Govee lights (govee-lan): 1 added; found in the last scan: 1" in text)
        assertTrue("  messages rejected as out of spec: 0" in text)
        assertTrue("#1 Govee H6076 (light) - govee-lan - id govee-…724E - address 192.168.*.23" in text)
        assertTrue("online; last reading 4 s ago; needs pairing: false; security key changed: false; firmware 1.00.18" in text)
        assertTrue("kinds of reading: PowerState, BrightnessState" in text)
        assertTrue("  pair-setup method used in this session: 0" in text)
        assertTrue("NetGuard: allowed 120, refused 0, inbound dropped 0" in text)
        assertTrue("Census: 1 address(es) answered" in text && "_googlecast._tcp x1" in text && "_hap._tcp x1" in text)
        assertTrue("1 min ago - #1 Govee H6076 (light): went offline" in text)
        assertTrue("scan found 3" in text)
        assertTrue("UTC+01:00" in text)
        assertFalse("192.168.1.40" in text, "the census must not list addresses")
        assertFalse("Hallway" in text, "the census must not list names")
    }

    @Test
    fun hostileTextCannotBreakTheReportOrCarrySecrets() {
        val text = DiagnosticsReport.build(
            input(
                devices = listOf(device(1, listOf("problem: bad\u0000line\r\nwith control\u0007 characters", "x".repeat(5_000), "access_code=12345678 password: hunter2"))),
                log = listOf("tok eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9 mac 1F:80:C5:32:32:36:72:4E serial 01P00A123456789 id ebf5889331f7b82250fasi"),
            ),
        )
        assertFalse(text.any { it.isISOControl() && it != '\n' }, "no control characters but newlines")
        assertTrue(text.lines().all { it.length <= 330 }, "every line is bounded")
        for (secret in listOf("12345678", "hunter2", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", "1F:80:C5:32:32:36", "01P00A123456789", "ebf5889331f7b82250fasi")) {
            assertFalse(secret in text, "leaked: $secret")
        }
    }

    @Test
    fun namesTheOwnerChoseAndAddressesInFreeTextAreMasked() {
        val text = DiagnosticsReport.build(
            input(
                devices = listOf(device(1, listOf("problem: could not reach Gustavo's lamp at 192.168.1.77"))),
                activity = listOf(LedgerEntry(now - 5_000, "#1 Govee H6076 (light)", "Running scene Movie night")),
                log = listOf("found Gustavo's lamp at 192.168.1.77", "scene movie NIGHT ran; the Kitchen is fine", "Kitchenette is not the same word"),
            ).copy(personalNames = listOf("Gustavo's lamp", "Movie night", "Kitchen", "TV")),
        )
        assertFalse("Gustavo" in text, text)
        assertFalse("192.168.1.77" in text, text)
        assertFalse("Movie night" in text || "movie NIGHT" in text, text)
        assertTrue("found (name) at 192.168.*.77" in text, text)
        assertTrue("the (name) is fine" in text, text)
        assertTrue("Kitchenette is not the same word" in text, "only whole words are replaced")
        assertTrue("Running scene (name)" in text, text)
    }

    @Test
    fun theScanAdviceAppearsOnlyWhenNothingWasFound() {
        val systems = listOf(
            SystemReport("Govee lights", "govee-lan", 0, 0, "Turn on LAN Control in the Govee app", emptyList()),
            SystemReport("Kasa plugs", "kasa-legacy", 1, 2, "Older firmware only", emptyList()),
            SystemReport("Tuya bulbs", "tuya-local", 1, null, "by address", emptyList()),
        )
        val text = DiagnosticsReport.build(input(systems = systems))
        assertTrue("Govee lights (govee-lan): 0 added; found in the last scan: 0 - Turn on LAN Control in the Govee app" in text, text)
        assertTrue("Kasa plugs (kasa-legacy): 1 added; found in the last scan: 2\n" in text, text)
        assertTrue("Tuya bulbs (tuya-local): 1 added\n" in text, text)
    }

    @Test
    fun versionNumbersInTheHeaderAreNotTakenForAddresses() {
        val text = DiagnosticsReport.build(input().copy(header = listOf("Java 21.0.9.1 on Windows 10.0.19045.1")))
        assertTrue("Java 21.0.9.1 on Windows 10.0.19045.1" in text, text)
    }

    @Test
    fun theWholeReportIsBounded() {
        val noisy = (1..70).map { device(it, List(60) { n -> "line $n " + "y".repeat(250) }) }
        val text = DiagnosticsReport.build(input(devices = noisy))
        assertTrue(text.length <= DiagnosticsReport.MAX_CHARS + 100, "length ${text.length}")
        assertTrue(text.trimEnd().endsWith("(report cut at ${DiagnosticsReport.MAX_CHARS} characters)"))
    }

    @Test
    fun aLongDeviceListIsCountedNotDumped() {
        val text = DiagnosticsReport.build(input(devices = (1..75).map { device(it) }))
        assertTrue("(15 more devices not listed)" in text)
        assertFalse("#61 " in text)
    }
}
