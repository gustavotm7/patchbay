package dev.patchbay.core.home

import dev.patchbay.core.discovery.Finding
import dev.patchbay.core.model.AdapterId
import dev.patchbay.core.model.ControlPath
import dev.patchbay.core.model.Device
import dev.patchbay.core.model.DeviceId
import dev.patchbay.core.model.DeviceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExposureReportTest {
    private fun dev(id: String, name: String, path: ControlPath) = Device(
        DeviceId(id), name, DeviceKind.LIGHT, "Govee", "H6076", null, null, path, emptyList(), AdapterId("govee-lan"),
    )

    private val lamp = LiveDevice(dev("govee-1", "Govee H6076", ControlPath.LOCAL_AFTER_SETUP), null, "192.168.1.23")
    private val census = listOf(
        Finding("192.168.1.23", emptySet(), setOf("_matter._tcp"), emptySet(), null, null, null), // same device
        Finding("192.168.1.30", setOf("tv-1"), setOf("_googlecast._tcp"), setOf(8009), "Living TV", "Chromecast", null),
        Finding("192.168.1.47", emptySet(), setOf("_http._tcp"), setOf(80), null, null, "lwIP"),
    )

    @Test
    fun foldsCensusIntoManagedAndClassifiesTheRest() {
        val r = ExposureReport.build(listOf(lamp), census, listOf(dev("kidde", "Smoke / CO alarm", ControlPath.CLOUD_ONLY)), generatedAt = "now")
        assertEquals(4, r.entries.size)
        assertTrue(r.entries.single { it.managed }.detail.contains("Matter: local"))
        assertEquals(ControlPath.LOCAL_AFTER_SETUP, r.entries.single { it.name == "Living TV" }.path)
        assertEquals(ControlPath.UNKNOWN, r.entries.single { it.address == "192.168.1.47" }.path)
        assertEquals(1, r.cloudOnly.size)
    }

    @Test
    fun reportSuggestsRulesOnlyForManagedDevicesAndHasNoSecrets() {
        val text = ExposureReport.build(listOf(lamp), census, generatedAt = "2026-11-05 19:30").text()
        assertTrue("option src_ip '192.168.1.23'" in text)
        assertFalse("option src_ip '192.168.1.30'" in text) // Cast isn't managed yet, so no rule is suggested
        assertTrue("not applied" in text)
        assertTrue("## Cloud-only" in text)
    }
}
