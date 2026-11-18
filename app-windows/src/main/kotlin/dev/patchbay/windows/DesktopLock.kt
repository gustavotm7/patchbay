package dev.patchbay.windows

import dev.patchbay.core.lock.AppLock
import dev.patchbay.core.lock.PinHash
import dev.patchbay.core.store.HomeStore
import dev.patchbay.ui.screens.LockController
import kotlinx.coroutines.flow.StateFlow

/**
 * Windows app lock: a Patchbay PIN (PBKDF2 hash in the encrypted DB) until Windows Hello support lands.
 * Locks at start and after the window has been unfocused for the auto-lock time.
 */
class DesktopLock(private val store: HomeStore) : LockController {
    private val lock = AppLock(clock = System::currentTimeMillis).also { l ->
        // The back-off survives a restart: closing and reopening the app must not give a guesser a fresh set of attempts.
        store.setting(BACKOFF_KEY)?.split(',')?.let { p -> p.getOrNull(0)?.toIntOrNull()?.let { f -> p.getOrNull(1)?.toLongOrNull()?.let { until -> l.restoreBackoff(f, until) } } }
        l.onBackoffChanged = { failures, until -> store.putSetting(BACKOFF_KEY, "$failures,$until") }
    }

    override val state: StateFlow<AppLock.State> = lock.state
    override val usesSystemAuth: Boolean = false
    override val pinIsSet: Boolean get() = store.setting(KEY) != null

    override fun setPin(pin: String) {
        if (!PinHash.isValidPin(pin)) return
        store.putSetting(KEY, PinHash.hash(pin))
        lock.unlocked()
    }

    override fun tryPin(pin: String): LockController.PinResult {
        lock.waitMs().takeIf { it > 0 }?.let { return LockController.PinResult.Wait(it) }
        val stored = store.setting(KEY) ?: return LockController.PinResult.Wrong
        val ok = PinHash.verify(pin, stored)
        lock.attempt(ok)
        return when {
            ok -> LockController.PinResult.Ok
            lock.waitMs() > 0 -> LockController.PinResult.Wait(lock.waitMs())
            else -> LockController.PinResult.Wrong
        }
    }

    fun lockNow() = lock.lockNow()
    fun onFocusLost() = lock.onBackground()
    fun onFocusGained() = lock.onForeground()

    private companion object {
        const val KEY = "lock.pin.pbkdf2"
        const val BACKOFF_KEY = "lock.pin.backoff"
    }
}
