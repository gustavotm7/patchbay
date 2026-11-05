package dev.patchbay.core.store

import java.io.File
import java.security.SecureRandom

/**
 * The database's data-encryption key: 256 random bits, stored only in wrapped form
 * ([KeyWrapper]) at `data/dek.wrapped`. Created on first start, then reused.
 */
public object DataKey {
    private const val FILE = "dek.wrapped"
    public const val SIZE: Int = 32

    public fun loadOrCreate(dataDir: File, wrapper: KeyWrapper): ByteArray {
        val f = File(dataDir, FILE)
        if (f.exists()) {
            val key = wrapper.unwrap(f.readBytes())
            check(key.size == SIZE) { "unwrapped key has the wrong size" }
            return key
        }
        val key = ByteArray(SIZE).also { SecureRandom().nextBytes(it) }
        val tmp = File(dataDir, "$FILE.tmp")
        tmp.writeBytes(wrapper.wrap(key))
        check(tmp.renameTo(f)) { "could not store wrapped key" }
        return key
    }

    /** Overwrites key material once it's no longer needed (best effort on the JVM). */
    public fun zeroise(key: ByteArray) {
        key.fill(0)
    }
}
