package dev.patchbay.android

import android.app.Application
import dev.patchbay.core.boot.Bootstrap
import dev.patchbay.core.boot.Services
import dev.patchbay.core.lock.AppLock

/** Process start: Bootstrap runs exactly once, here (CLAUDE.md conventions). */
class PatchbayApplication : Application() {
    lateinit var services: Services
        private set

    /** Process-wide app lock: starts locked; auto-locks after a minute in the background. */
    val lock: AndroidLock by lazy { AndroidLock(AppLock(clock = System::currentTimeMillis)) }

    override fun onCreate() {
        super.onCreate()
        // App-private storage only (no external storage, excluded from backup).
        services = Bootstrap.start(
            platform = "android",
            root = filesDir,
            keyWrapper = KeystoreKeyWrapper(this),
            openDb = { file, key -> AndroidStorage.open(this, file, key) },
        )
    }
}
