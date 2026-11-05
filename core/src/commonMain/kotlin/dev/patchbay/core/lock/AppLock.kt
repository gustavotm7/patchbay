package dev.patchbay.core.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App lock state (privacy invariant 6). Platform shells decide *how* to unlock (BiometricPrompt, PIN,
 * Windows Hello); this decides *when* the app is locked:
 * - at start,
 * - after [autoLockMs] in the background,
 * - and immediately on [lockNow].
 * Failed PIN attempts back off exponentially.
 */
public class AppLock(
    autoLockMs: Long = DEFAULT_AUTO_LOCK_MS,
    private val clock: () -> Long,
) {
    public enum class State { LOCKED, UNLOCKED }

    private val _state = MutableStateFlow(State.LOCKED)
    public val state: StateFlow<State> = _state.asStateFlow()

    public var autoLockMs: Long = autoLockMs
        set(v) {
            require(v in 0..24 * 3_600_000L)
            field = v
        }

    private var backgroundedAt: Long? = null
    private var failures = 0
    private var blockedUntil = 0L

    public fun onBackground() {
        backgroundedAt = clock()
    }

    public fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (clock() - since >= autoLockMs) _state.value = State.LOCKED
    }

    public fun lockNow() {
        _state.value = State.LOCKED
    }

    /** Call after a successful platform authentication (biometric / device credential / Windows Hello). */
    public fun unlocked() {
        failures = 0
        blockedUntil = 0
        _state.value = State.UNLOCKED
    }

    /** Milliseconds until another PIN attempt is allowed (0 = now). */
    public fun waitMs(): Long = (blockedUntil - clock()).coerceAtLeast(0)

    /** Records a PIN attempt. After 3 failures, waits grow 5 s, 10 s, 20 s … capped at 5 min. */
    public fun attempt(correct: Boolean): Boolean {
        if (waitMs() > 0) return false
        if (correct) {
            unlocked()
            return true
        }
        failures++
        if (failures >= 3) blockedUntil = clock() + minOf(5_000L shl (failures - 3).coerceAtMost(6), 300_000L)
        return false
    }

    public companion object {
        public const val DEFAULT_AUTO_LOCK_MS: Long = 60_000
    }
}
