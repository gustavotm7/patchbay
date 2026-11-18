package dev.patchbay.android

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import dev.patchbay.core.boot.Bootstrap
import dev.patchbay.core.boot.Services
import dev.patchbay.core.lock.AppLock

/**
 * Process start: Bootstrap runs exactly once. Normally that is here, at start. The one exception: when Android restarts the process while the phone is locked
 * (the background-watching service is sticky), the database key is unusable until the owner unlocks, and booting then would leave the whole session on an empty,
 * in-memory home. So in that case nothing is booted until something asks for [services] after unlock, which is the activity.
 */
class PatchbayApplication : Application() {
    private var booted: Services? = null

    val services: Services
        get() = booted ?: synchronized(this) { booted ?: boot().also { booted = it } }

    /** Process-wide app lock: starts locked; auto-locks after a minute in the background. */
    val lock: AndroidLock by lazy { AndroidLock(AppLock(clock = System::currentTimeMillis)) }

    /** True while the phone is locked, when the Keystore key that protects the database cannot be used (ADR 0003: unlocked-device-required). */
    fun deviceLocked(): Boolean = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

    override fun onCreate() {
        super.onCreate()
        if (!deviceLocked()) services // boot now, at process start
    }

    private fun boot(): Services =
        // App-private storage only (no external storage, excluded from backup).
        Bootstrap.start(
            platform = "android",
            root = filesDir,
            keyWrapper = KeystoreKeyWrapper(this),
            openDb = { file, key -> AndroidStorage.open(this, file, key) },
        )
}
