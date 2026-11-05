package dev.patchbay.core.log

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

public enum class Level { DEBUG, INFO, WARN, ERROR }

/**
 * The session log (CLAUDE.md conventions). Bootstrap creates exactly one at startup:
 * `patchbay_<platform>_<YYYYMMDD-HHMMSS>.log` in [dir], used for the whole session.
 *
 * - Every line passes through [Redactor].
 * - Rotation by size: when the file exceeds [maxBytes], it's renamed to `….log.1` (shifting older parts
 *   up to [maxParts]) and a fresh file continues under the same name.
 * - Retention: at startup, sessions beyond the newest [keepSessions] are deleted (with their parts).
 * - Logs never leave the device except through an explicit user export (Phase 1 settings screen).
 */
public class SessionLog private constructor(
    public val file: File,
    private val maxBytes: Long,
    private val maxParts: Int,
    private val zone: ZoneId,
    private val minLevel: Level,
) : AutoCloseable {

    private var writer: Writer = open()
    private var written: Long = file.length()

    private fun open(): Writer = OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)

    @Synchronized
    public fun log(level: Level, tag: String, message: String, error: Throwable? = null) {
        if (level < minLevel) return
        val time = LINE_TIME.format(Instant.now().atZone(zone))
        val err = error?.let { " [${it.javaClass.simpleName}: ${it.message ?: ""}]" } ?: ""
        val line = Redactor.redact("$time ${level.name.padEnd(5)} ${tag.take(24)}: $message$err")
            .replace('\n', ' ').replace('\r', ' ') + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8).size
        if (written + bytes > maxBytes) rotate()
        writer.write(line)
        writer.flush()
        written += bytes
    }

    public fun info(tag: String, message: String): Unit = log(Level.INFO, tag, message)
    public fun warn(tag: String, message: String, error: Throwable? = null): Unit = log(Level.WARN, tag, message, error)
    public fun error(tag: String, message: String, error: Throwable? = null): Unit = log(Level.ERROR, tag, message, error)
    public fun debug(tag: String, message: String): Unit = log(Level.DEBUG, tag, message)

    private fun rotate() {
        writer.close()
        // Oldest part goes first, then everything shifts up by one: .1 -> .2 … and the live file -> .1
        File(file.path + ".$maxParts").let { if (it.exists()) it.delete() }
        for (i in maxParts - 1 downTo 1) {
            val from = File(file.path + ".$i")
            if (from.exists()) from.renameTo(File(file.path + ".${i + 1}"))
        }
        file.renameTo(File(file.path + ".1"))
        writer = open()
        written = 0
    }

    @Synchronized
    override fun close() {
        writer.close()
    }

    public companion object {
        private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        private val NAME = Regex("^patchbay_([a-z0-9]+)_(\\d{8}-\\d{6})\\.log(\\.\\d+)?$")

        public fun fileName(platform: String, startedAt: Instant, zone: ZoneId): String {
            require(platform.matches(Regex("[a-z0-9]+"))) { "platform must be lowercase alphanumeric" }
            return "patchbay_${platform}_${NAME_TIME.format(startedAt.atZone(zone))}.log"
        }

        /** Creates the session log. Called once, by Bootstrap. */
        public fun start(
            dir: File,
            platform: String,
            startedAt: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
            maxBytes: Long = 2L * 1024 * 1024,
            maxParts: Int = 3,
            keepSessions: Int = 10,
            minLevel: Level = Level.INFO,
        ): SessionLog {
            require(maxBytes >= 1024 && maxParts >= 1 && keepSessions >= 1)
            dir.mkdirs()
            prune(dir, keepSessions - 1)
            val f = File(dir, fileName(platform, startedAt, zone))
            return SessionLog(f, maxBytes, maxParts, zone, minLevel)
        }

        /** Deletes all but the newest [keep] sessions (a session = its .log plus rotated parts). */
        internal fun prune(dir: File, keep: Int) {
            val sessions = (dir.listFiles() ?: return)
                .mapNotNull { f -> NAME.matchEntire(f.name)?.let { m -> "${m.groupValues[1]}_${m.groupValues[2]}" to f } }
                .groupBy({ it.first }, { it.second })
            sessions.keys.sortedByDescending { it.substringAfter('_') }.drop(keep).forEach { k ->
                sessions.getValue(k).forEach { it.delete() }
            }
        }
    }
}
