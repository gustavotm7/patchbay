package dev.patchbay.android

import android.app.Application
import dev.patchbay.core.boot.Bootstrap
import dev.patchbay.core.boot.Services

/** Process start: Bootstrap runs exactly once, here (CLAUDE.md conventions). */
class PatchbayApplication : Application() {
    lateinit var services: Services
        private set

    override fun onCreate() {
        super.onCreate()
        // App-private storage only (no external storage, excluded from backup).
        services = Bootstrap.start("android", filesDir)
    }
}
