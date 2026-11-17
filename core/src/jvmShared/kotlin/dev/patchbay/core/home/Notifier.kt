package dev.patchbay.core.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Short messages worth interrupting for (a changed security key, a device going offline), with a per-kind rate limit and the owner's quiet hours. */
internal class Notifier(private val ctx: HomeContext) {
    private val _quiet = MutableStateFlow<QuietHours?>(null)
    val quietHours: StateFlow<QuietHours?> = _quiet.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: Flow<String> = _messages
    private val lastNotified = ConcurrentHashMap<String, Long>()

    fun load() {
        ctx.store?.setting("quiet")?.let { _quiet.value = QuietHours.parse(it) }
    }

    suspend fun setQuietHours(quiet: QuietHours?) {
        _quiet.value = quiet
        ctx.store?.putSetting("quiet", quiet?.text() ?: "")
    }

    /**
     * At most one notification per device and kind per hour, so a flapping device can't spam. Outside [urgent] alerts, nothing is sent during quiet
     * hours (it is still in Activity).
     */
    fun notify(key: String, message: String, windowMs: Long = 3_600_000, urgent: Boolean = false) {
        val now = ctx.clock()
        if (!urgent) {
            val q = _quiet.value
            if (q != null) {
                val t = java.time.Instant.ofEpochMilli(now).atZone(ctx.zone)
                if (q.contains(t.hour * 60 + t.minute)) return
            }
        }
        val last = lastNotified[key]
        if (last != null && now - last < windowMs) return
        lastNotified[key] = now
        _messages.tryEmit(message)
    }
}
