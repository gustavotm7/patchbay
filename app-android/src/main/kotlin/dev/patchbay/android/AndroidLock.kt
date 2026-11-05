package dev.patchbay.android

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.patchbay.core.lock.AppLock
import dev.patchbay.ui.screens.LockController
import kotlinx.coroutines.flow.StateFlow

/**
 * Android app lock (privacy invariant 6): the system BiometricPrompt, allowing a strong biometric or the device
 * PIN/pattern/password. The [AppLock] lives in [PatchbayApplication], so lock state survives activity recreation.
 * If the phone has no screen lock at all, the device itself is unprotected; we unlock and don't pretend otherwise.
 */
class AndroidLock(internal val lock: AppLock) : LockController {
    private var activity: FragmentActivity? = null

    override val state: StateFlow<AppLock.State> = lock.state
    override val usesSystemAuth: Boolean = true

    fun attach(a: FragmentActivity) {
        activity = a
    }

    fun detach(a: FragmentActivity) {
        if (activity === a) activity = null
    }

    override fun requestSystemAuth() {
        val a = activity ?: return
        val allowed = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        if (BiometricManager.from(a).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            lock.unlocked() // no screen lock configured on the phone
            return
        }
        val prompt = BiometricPrompt(
            a, ContextCompat.getMainExecutor(a),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = lock.unlocked()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Patchbay")
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }
}
