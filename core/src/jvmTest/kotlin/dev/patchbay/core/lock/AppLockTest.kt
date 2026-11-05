package dev.patchbay.core.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
    private var now = 0L
    private val lock = AppLock(autoLockMs = 60_000) { now }

    @Test
    fun startsLockedAndAutoLocksAfterBackgroundTimeout() {
        assertEquals(AppLock.State.LOCKED, lock.state.value)
        lock.unlocked()
        lock.onBackground(); now += 30_000; lock.onForeground()
        assertEquals(AppLock.State.UNLOCKED, lock.state.value)
        lock.onBackground(); now += 60_000; lock.onForeground()
        assertEquals(AppLock.State.LOCKED, lock.state.value)
    }

    @Test
    fun failedPinsBackOff() {
        assertFalse(lock.attempt(false)); assertFalse(lock.attempt(false))
        assertEquals(0, lock.waitMs())
        assertFalse(lock.attempt(false))
        assertEquals(5_000, lock.waitMs())
        assertFalse(lock.attempt(true)) // even the right PIN has to wait
        now += 5_000
        assertTrue(lock.attempt(true))
        assertEquals(AppLock.State.UNLOCKED, lock.state.value)
    }

    @Test
    fun pinHashRoundTripAndRejectsWrongOrMalformed() {
        val h = PinHash.hash("4821", iterations = 20_000) // fewer iterations keep the test quick
        assertTrue(PinHash.verify("4821", h))
        assertFalse(PinHash.verify("4822", h))
        assertFalse(PinHash.verify("4821", h.replace("pbkdf2-sha256", "md5")))
        assertFalse(PinHash.verify("4821", "garbage"))
        assertFalse(h.contains("4821"))
        assertFalse(PinHash.isValidPin("12a4")); assertFalse(PinHash.isValidPin("123"))
    }
}
