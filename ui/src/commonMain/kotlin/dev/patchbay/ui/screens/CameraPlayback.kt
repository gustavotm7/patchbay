package dev.patchbay.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.patchbay.core.adapter.StreamLease

/**
 * Platform video playback, supplied by the app shell (ADR 0008). Android provides one built on a guarded player;
 * desktop provides none yet, and the camera panel then shows status and the credential-free address only.
 */
fun interface CameraPlayer {
    @Composable
    fun Render(lease: StreamLease, modifier: Modifier)
}

val LocalCameraPlayer = staticCompositionLocalOf<CameraPlayer?> { null }
