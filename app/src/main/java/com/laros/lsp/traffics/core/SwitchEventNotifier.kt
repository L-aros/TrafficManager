package com.laros.lsp.traffics.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.laros.lsp.traffics.R

class SwitchEventNotifier(private val context: Context) {
    fun notify(event: SwitchEvent) {
        val dedupKey = "${event.success}|${event.targetSlot}|${event.reason}|${event.transport}"
        val now = System.currentTimeMillis()
        if (!event.success && dedupKey == lastEventKey && now - lastEventAtMs < 30_000L) {
            return
        }
        lastEventKey = dedupKey
        lastEventAtMs = now

        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)

        val simLabel = "SIM${event.targetSlot + 1}"
        val title = when {
            event.success ->
                context.getString(R.string.notify_switch_success_title, simLabel)
            event.message.contains("verify_failed", ignoreCase = true) ->
                context.getString(R.string.notify_switch_verify_failed_title, simLabel)
            else ->
                context.getString(R.string.notify_switch_failed_title, simLabel)
        }
        val text = context.getString(R.string.notify_event_text, event.reason, event.transport)
        val style = NotificationCompat.BigTextStyle()
            .bigText("$text\n${event.message.take(500)}")
        val notification = NotificationCompat.Builder(context, AutoSwitchServiceChannels.EVENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setAutoCancel(true)
            .setPriority(
                if (event.success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH
            )
            .build()
        manager.notify(AutoSwitchServiceChannels.EVENT_NOTIFY_BASE + (System.currentTimeMillis() % 10000L).toInt(), notification)
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                AutoSwitchServiceChannels.CHANNEL_ID,
                context.getString(R.string.notify_channel_service),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                AutoSwitchServiceChannels.EVENT_CHANNEL_ID,
                context.getString(R.string.notify_channel_events),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        private var lastEventKey: String? = null
        private var lastEventAtMs: Long = 0L
    }
}
