package dev.patchbay.core.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import dev.patchbay.core.db.PatchbayDb
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Desktop/hub encrypted database (ADR 0003): SQLCipher 4 format via SQLite3MultipleCiphers. */
public object DesktopStorage {

    public fun open(file: File, key: ByteArray): SqlDriver {
        require(key.size == DataKey.SIZE) { "key must be 256 bits" }
        val props = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build().toProperties()
        return JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", props, PatchbayDb.Schema)
    }

    /** The best available wrapper on this OS. */
    public fun keyWrapper(dataDir: File): KeyWrapper = if (Platform.isWindows()) DpapiKeyWrapper else FileKeyWrapper(dataDir)
}

/** Windows DPAPI, CurrentUser scope, with app-specific entropy. Protects against other users and offline disk theft (with BitLocker). */
public object DpapiKeyWrapper : KeyWrapper {
    private val entropy = "dev.patchbay.dek.v1".toByteArray()
    override val kind: String = "windows-dpapi"
    override val osProtected: Boolean = true
    override fun wrap(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain, entropy, 0, "Patchbay", null)
    override fun unwrap(wrapped: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(wrapped, entropy, 0, null)
}

/**
 * Fallback for the Linux hub and non-Windows dev machines: the key is stored as-is in a file readable only
 * by the owner. This is weaker (THREAT_MODEL §7, open question 2): anyone who can read the file can open
 * the database. [osProtected] is false so the UI and logs say so.
 */
public class FileKeyWrapper(private val dataDir: File) : KeyWrapper {
    override val kind: String = "file-owner-only"
    override val osProtected: Boolean = false

    override fun wrap(plain: ByteArray): ByteArray {
        restrict(dataDir)
        return plain.copyOf()
    }

    override fun unwrap(wrapped: ByteArray): ByteArray = wrapped.copyOf()

    private fun restrict(dir: File) {
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }
}
