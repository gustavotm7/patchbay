package dev.patchbay.core.log

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionLogTest {
    private val dir: File = Files.createTempDirectory("pblog").toFile()
    private val t0 = Instant.parse("2026-11-05T19:30:05Z")

    @Test
    fun namesFileByPlatformAndStartTime() {
        assertEquals("patchbay_android_20261105-193005.log", SessionLog.fileName("android", t0, ZoneOffset.UTC))
    }

    @Test
    fun writesRedactedSingleLineEntries() {
        SessionLog.start(dir, "windows", t0, ZoneOffset.UTC).use { log ->
            log.info("bambu", "connecting with access_code=87654321\nsecond line")
            val text = log.file.readText()
            assertFalse("87654321" in text)
            assertEquals(1, text.lines().count { it.isNotBlank() })
            assertTrue(text.contains("INFO  bambu: connecting"))
        }
    }

    @Test
    fun rotatesBySize(): Unit {
        SessionLog.start(dir, "hub", t0, ZoneOffset.UTC, maxBytes = 1024, maxParts = 2).use { log ->
            repeat(100) { log.info("t", "line $it " + "x".repeat(40)) }
            assertTrue(File(log.file.path + ".1").exists())
            assertTrue(File(log.file.path + ".2").exists())
            assertFalse(File(log.file.path + ".3").exists())
            assertTrue(log.file.length() <= 1024)
        }
    }

    @Test
    fun keepsOnlyRecentSessions() {
        repeat(5) { i -> SessionLog.start(dir, "android", t0.plusSeconds(i * 60L), ZoneOffset.UTC, keepSessions = 3).use { it.info("t", "s$i") } }
        val logs = dir.listFiles()!!.map { it.name }.filter { it.endsWith(".log") }.sorted()
        assertEquals(3, logs.size)
        assertEquals("patchbay_android_20261105-193405.log", logs.last())
    }
}
