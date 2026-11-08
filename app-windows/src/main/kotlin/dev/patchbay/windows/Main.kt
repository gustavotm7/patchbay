package dev.patchbay.windows

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.patchbay.core.boot.DesktopBootstrap
import dev.patchbay.core.home.LiveHome
import dev.patchbay.simulators.DemoHome
import java.io.File
import dev.patchbay.ui.DesktopFonts
import dev.patchbay.ui.designsystem.FormFactor
import dev.patchbay.ui.designsystem.Fmt
import dev.patchbay.ui.designsystem.PatchbayTheme
import dev.patchbay.core.store.HomeStore
import dev.patchbay.ui.screens.AppSettings
import dev.patchbay.ui.screens.Appearance
import dev.patchbay.ui.screens.DesktopApp
import dev.patchbay.ui.screens.LockGate
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.isTraySupported
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * Windows shell: PIN app lock (DesktopLock), live LAN devices + the demo home in the multi-pane layout.
 * Native window decorations (decided 2026-11-05; DESIGN_SPEC §7): native resize and Windows 11 snap layouts.
 */
fun main() {
    // Bootstrap once, at process start. Installed: data lives under %LOCALAPPDATA%\Patchbay (per-user).
    // Portable: the zip contains a portable.txt next to Patchbay.exe, and then everything (database, key, logs) lives in
    // PatchbayData beside it, so deleting the folder removes it all. The key is wrapped by Windows for this user and PC.
    val exeDir = System.getProperty("jpackage.app-path")?.let { File(it).parentFile }
    val portable = exeDir != null && File(exeDir, "portable.txt").isFile
    val root = when {
        portable -> File(exeDir, "PatchbayData")
        else -> System.getenv("LOCALAPPDATA")?.let { File(it, "Patchbay") } ?: File(System.getProperty("user.home"), ".patchbay")
    }
    val services = DesktopBootstrap.start("windows", root)
    Runtime.getRuntime().addShutdownHook(Thread { services.close() })
    val ffmpeg = dev.patchbay.core.media.FfmpegLocator.find(listOfNotNull(exeDir, root))
    app(services.home, DesktopLock(services.store), services.store, services.keyKind, root, portable, dev.patchbay.core.media.CameraVideo(services.netGuard, ffmpeg))
}

/** The tray icon: a teal disc with a dark centre, drawn in code so there is no image file to ship. */
private object TrayIcon : Painter() {
    override val intrinsicSize: Size = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawCircle(Color(0xFF2DD4BF), radius = size.minDimension * 0.46f)
        drawCircle(Color(0xFF0A0B0D), radius = size.minDimension * 0.17f)
    }
}

private fun app(live: LiveHome, lock: DesktopLock, store: HomeStore, keyKind: String, root: File, portable: Boolean, video: dev.patchbay.core.media.CameraVideo) = application {
    val home = DemoHome.home()
    val now = Fmt.timeOfDay(DemoHome.DEMO_NOW.value)
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    var trayMode by remember { mutableStateOf(store.setting("ui.tray") == "true") }
    val trayOn = trayMode && isTraySupported
    val trayState = rememberTrayState()
    // Problems worth interrupting for (a changed security key, a device that stopped answering), as tray notifications.
    LaunchedEffect(trayOn) {
        if (trayOn) live.notifications.collect { trayState.sendNotification(Notification("Patchbay", it, Notification.Type.Warning)) }
    }
    if (trayOn) {
        Tray(
            state = trayState,
            icon = TrayIcon,
            tooltip = "Patchbay",
            onAction = { visible = true },
            menu = {
                Item("Open Patchbay") { visible = true }
                Item("All off") { scope.launch { live.allOff() } }
                Item("Lock now") { lock.lockNow() }
                Separator()
                Item("Quit") { exitApplication() }
            },
        )
    }
    Window(
        visible = visible,
        // With the tray on, closing hides the window and locks it; the app keeps running. Otherwise closing quits.
        onCloseRequest = { if (trayOn) { lock.lockNow(); visible = false } else exitApplication() },
        title = "Patchbay",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        // Auto-lock after the window has been unfocused for the auto-lock time.
        DisposableEffect(window) {
            val l = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) = lock.onFocusGained()
                override fun windowLostFocus(e: WindowEvent?) = lock.onFocusLost()
            }
            window.addWindowFocusListener(l)
            onDispose { window.removeWindowFocusListener(l) }
        }
        var appearance by remember { mutableStateOf(Appearance.fromName(store.setting("ui.appearance"))) }
        val settings = AppSettings(
            appearance = appearance,
            onAppearance = { appearance = it; store.putSetting("ui.appearance", it.name) },
            onLockNow = lock::lockNow,
            keyProtection = keyKind,
            dataLocation = root.absolutePath,
            portable = portable,
            version = "0.1.0",
            trayMode = if (isTraySupported) trayMode else null,
            onTrayMode = { trayMode = it; store.putSetting("ui.tray", it.toString()) },
        )
        PatchbayTheme(appearance.palette(isSystemInDarkTheme()), DesktopFonts.typefaces, FormFactor.DESKTOP) {
            androidx.compose.runtime.CompositionLocalProvider(dev.patchbay.ui.screens.LocalCameraPlayer provides remember { DesktopCameraPlayer(video) }) {
                LockGate(lock) { DesktopApp(home, DemoHome.trends, now, live, settings) }
            }
        }
    }
}
