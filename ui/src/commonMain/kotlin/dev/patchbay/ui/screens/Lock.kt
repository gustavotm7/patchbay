package dev.patchbay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.patchbay.core.lock.AppLock
import dev.patchbay.ui.designsystem.ButtonKind
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.PButton
import dev.patchbay.ui.designsystem.PText
import dev.patchbay.ui.designsystem.Patchbay
import dev.patchbay.ui.designsystem.Shapes
import dev.patchbay.ui.designsystem.Space
import dev.patchbay.ui.designsystem.VSpace
import kotlinx.coroutines.flow.StateFlow

/** How the platform unlocks. Android: BiometricPrompt (biometric or device PIN). Windows: Patchbay PIN. */
interface LockController {
    val state: StateFlow<AppLock.State>
    val usesSystemAuth: Boolean

    /** Android: shows BiometricPrompt. The result arrives by [state] changing. */
    fun requestSystemAuth() {}

    /** PIN mode only. */
    val pinIsSet: Boolean get() = false
    fun setPin(pin: String) {}
    fun tryPin(pin: String): PinResult = PinResult.Wrong

    sealed interface PinResult {
        data object Ok : PinResult
        data object Wrong : PinResult
        data class Wait(val ms: Long) : PinResult
    }
}

/** Shows [content] only while unlocked. The lock screen covers everything else (FLAG_SECURE stays on). */
@Composable
fun LockGate(lock: LockController?, content: @Composable () -> Unit) {
    if (lock == null) return content()
    val state by lock.state.collectAsState()
    if (state == AppLock.State.UNLOCKED) content() else LockScreen(lock)
}

@Composable
fun LockScreen(lock: LockController) {
    val c = Patchbay.colors
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.Center) {
        Column(Modifier.width(320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PText("PATCHBAY", Patchbay.type.legend, c.textSecondary)
            VSpace(Space.s)
            PText("Locked", Patchbay.type.title)
            VSpace(Space.xl)
            if (lock.usesSystemAuth) {
                LaunchedEffect(Unit) { lock.requestSystemAuth() }
                PButton("Unlock", { lock.requestSystemAuth() }, Modifier.fillMaxWidth(), ButtonKind.PRIMARY)
                VSpace(Space.s)
                PText("Fingerprint, face or your device PIN", Patchbay.type.caption, c.textTertiary)
            } else {
                PinPad(lock)
            }
        }
    }
}

@Composable
private fun PinPad(lock: LockController) {
    val c = Patchbay.colors
    var entry by remember { mutableStateOf("") }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val creating = !lock.pinIsSet

    PText(
        when {
            creating && firstEntry == null -> "Create a PIN (4–12 digits)"
            creating -> "Enter it again to confirm"
            else -> "Enter your PIN"
        },
        Patchbay.type.body, c.textSecondary,
    )
    VSpace(Space.m)
    // Masked: one square per digit, never the digits themselves.
    Row(Modifier.semantics { contentDescription = "${entry.length} digits entered" }, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        repeat(maxOf(4, entry.length)) { i ->
            Box(Modifier.size(12.dp).border(Space.hair, c.outline, Shapes.chip).background(if (i < entry.length) c.text else c.background, Shapes.chip))
        }
    }
    message?.let { VSpace(Space.s); PText(it, Patchbay.type.caption, c.alarm) }
    VSpace(Space.l)

    fun submit() {
        if (creating) {
            val first = firstEntry
            when {
                first == null -> { if (entry.length >= 4) { firstEntry = entry; message = null } else message = "At least 4 digits" }
                first == entry -> { lock.setPin(entry); message = null }
                else -> { firstEntry = null; message = "PINs didn't match — start again" }
            }
        } else {
            when (val r = lock.tryPin(entry)) {
                LockController.PinResult.Ok -> message = null
                LockController.PinResult.Wrong -> message = "Wrong PIN"
                is LockController.PinResult.Wait -> message = "Too many attempts — wait ${(r.ms + 999) / 1000} s"
            }
        }
        entry = ""
    }

    val keySize = if (Patchbay.formFactor == FormFactor.PHONE) Space.touchTarget + Space.m else 48.dp
    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            row.forEach { k ->
                PButton(
                    k,
                    {
                        when (k) {
                            "⌫" -> entry = entry.dropLast(1)
                            "OK" -> submit()
                            else -> if (entry.length < 12) entry += k
                        }
                    },
                    Modifier.width(keySize * 1.6f),
                    if (k == "OK") ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                    enabled = k != "OK" || entry.length >= 4,
                )
            }
        }
        VSpace(Space.s)
    }
}
