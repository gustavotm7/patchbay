package dev.patchbay.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive with the screen off so devices are still watched and automations still run, and turns
 * [dev.patchbay.core.home.LiveHome.notifications] into Android notifications (ADR 0011). Runs only while the owner has switched
 * "Keep watching in the background" on; the persistent notification has a Stop button. No wake lock, no boot start,
 * no network of its own: the work is the same LAN polling the app already does.
 *
 * Alert text names devices, so the lock-screen version is generic.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var nextAlertId = ALERT_BASE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as PatchbayApplication).services.store.putSetting(SETTING, "false")
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        val n = ongoing()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ONGOING_ID, n)
        if (!watching) {
            watching = true
            val live = (application as PatchbayApplication).services.home
            scope.launch { live.notifications.collect { alert(it) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watching = false
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "Watching in the background", NotificationManager.IMPORTANCE_MIN).apply {
            description = "The quiet notification that shows Patchbay is watching your devices."
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A device stopped answering, a print finished, or a device presented a different security key."
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    private fun open(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun ongoing(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Patchbay is watching your devices")
            .setContentText("Local network only. Tap to open.")
            .setContentIntent(open())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun alert(message: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val publicVersion = Notification.Builder(this, CH_ALERTS).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Patchbay alert").build()
        val n = Notification.Builder(this, CH_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Patchbay")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open())
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        nm.notify(nextAlertId, n)
        nextAlertId = if (nextAlertId >= ALERT_BASE + 50) ALERT_BASE else nextAlertId + 1 // at most 50 alerts shown; older ones are replaced
    }

    companion object {
        const val SETTING = "ui.background"
        private const val ACTION_STOP = "dev.patchbay.android.STOP_WATCHING"
        private const val CH_ONGOING = "watch"
        private const val CH_ALERTS = "alerts"
        private const val ONGOING_ID = 1
        private const val ALERT_BASE = 100
        @Volatile private var watching = false

        /** Starts the service from the foreground app; Android refuses this from the background, which is fine: it was already running or the owner will reopen the app. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WatchService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed right now (app not visible). The next time the app opens it tries again.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
