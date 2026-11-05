package dev.patchbay.core.boot

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootstrapTest {
    @Test
    fun startsInOrderAndCreatesOnlyExpectedFiles() {
        val root: File = Files.createTempDirectory("pbboot").toFile()
        DesktopBootstrap.start("hub", root).use { s ->
            s.store.putSetting("k", "v")
            assertEquals("file-owner-only", s.keyKind)
        }
        val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        assertTrue(files.any { it.matches(Regex("logs/patchbay_hub_\\d{8}-\\d{6}\\.log")) }, "$files")
        assertTrue("data/dek.wrapped" in files)
        assertTrue("data/patchbay.db" in files)
        // Restart: same key, same data.
        DesktopBootstrap.start("hub", root).use { s -> assertEquals("v", s.store.setting("k")) }
        val log = root.resolve("logs").listFiles()!!.first().readText()
        assertTrue("NOT OS-protected" in log)
    }
}
