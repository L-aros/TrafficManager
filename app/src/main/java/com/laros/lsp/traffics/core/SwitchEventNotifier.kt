package com.laros.lsp.traffics.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.laros.lsp.traffics.R
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class SwitchEventNotifier(private val context: Context) {
    fun notify(event: SwitchEvent) {
        val dedupKey = "${event.success}|${event.targetSlot}|${event.reason}|${event.transport}"
        val now = System.currentTimeMillis()
        if (dedupKey == lastEventKey && now - lastEventAtMs < DUPLICATE_EVENT_WINDOW_MS) {
            Log.d(TAG, "skip duplicate event: $dedupKey")
            return
        }
        lastEventKey = dedupKey
        lastEventAtMs = now

        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        cancelPendingTransitions(context)

        val simLabel = "SIM${event.targetSlot + 1}"
        val focusStatus = buildFocusStatus(event)
        val payload = EventPayload(
            token = UUID.randomUUID().toString(),
            title = buildDisplayTitle(simLabel, focusStatus),
            text = buildDisplaySubtitle(focusStatus),
            message = event.message,
            focusStatus = focusStatus
        )

        Log.d(TAG, "post main focus notification title=${payload.title} status=$focusStatus token=${payload.token}")
        manager.notify(
            AutoSwitchServiceChannels.EVENT_FOCUS_NOTIFY_ID,
            buildFocusNotification(payload, XiaomiFocusNotificationCompat.FocusMode.MAIN, silent = false)
        )
    }

    fun notifySummaryFromIntent(intent: Intent) {
        Log.d(TAG, "ignore stale summary transition action=${intent.action}")
        cancelPendingTransitions(context)
    }

    private fun postSummaryNotification(payload: EventPayload, source: String) {
        if (!advanceToSummary(context, payload.token)) {
            Log.d(TAG, "skip stale summary notification source=$source token=${payload.token}")
            return
        }
        cancelSummaryTransition(context, clearPhase = false)
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        Log.d(
            TAG,
            "post summary focus notification title=${payload.title} status=${payload.focusStatus} token=${payload.token} source=$source"
        )
        manager.notify(
            AutoSwitchServiceChannels.EVENT_FOCUS_NOTIFY_ID,
            buildFocusNotification(payload, XiaomiFocusNotificationCompat.FocusMode.SUMMARY, silent = true)
        )
    }

    private fun buildFocusNotification(
        payload: EventPayload,
        focusMode: XiaomiFocusNotificationCompat.FocusMode,
        silent: Boolean
    ): Notification {
        val builder = buildBaseNotificationBuilder(payload, AutoSwitchServiceChannels.FOCUS_CHANNEL_ID)
            .setTicker("${payload.title} ${payload.text}".trim())
            .setSilent(silent)
            .addExtras(android.os.Bundle().apply {
                putString(EXTRA_FOCUS_NOTIFY_ROLE, focusMode.name.lowercase(Locale.ROOT))
            })
        val notification = XiaomiFocusNotificationCompat.applyToEventNotification(
            context = context,
            builder = builder,
            notifyId = payload.token,
            focusTitle = payload.title,
            focusSubtitle = payload.text,
            focusStatus = payload.focusStatus,
            focusMode = focusMode
        )
        stripBigIslandCornerBadge(notification)
        return notification
    }

    private fun stripBigIslandCornerBadge(notification: Notification) {
        val extras = notification.extras ?: return
        sanitizeFocusParam(extras, EXTRA_MIUI_FOCUS_PARAM)
        sanitizeFocusParam(extras, EXTRA_MIUI_FOCUS_PARAM_V2)
    }

    private fun sanitizeFocusParam(extras: android.os.Bundle, key: String) {
        val raw = extras.getString(key) ?: return
        val sanitized = runCatching {
            val root = JSONObject(raw)
            val paramV2 = root.optJSONObject("param_v2")
            val paramIsland = paramV2?.optJSONObject("param_island")
            val bigIslandArea = paramIsland?.optJSONObject("bigIslandArea")
            if (bigIslandArea == null) {
                raw
            } else {
                bigIslandArea.remove("picInfo")
                root.toString()
            }
        }.getOrNull() ?: return
        extras.putString(key, sanitized)
    }

    private fun buildBaseNotificationBuilder(
        payload: EventPayload,
        channelId: String
    ): NotificationCompat.Builder {
        val style = NotificationCompat.BigTextStyle()
            .bigText(payload.text)
        val contentIntent = PendingIntent.getActivity(
            context,
            payload.token.hashCode(),
            Intent(context, com.laros.lsp.traffics.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(payload.title)
            .setContentText(payload.text)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis())
            .setPriority(
                if (payload.focusStatus == XiaomiFocusNotificationCompat.FocusStatus.SUCCESS) {
                    NotificationCompat.PRIORITY_DEFAULT
                } else {
                    NotificationCompat.PRIORITY_HIGH
                }
            )
    }

    private fun buildFocusStatus(event: SwitchEvent): XiaomiFocusNotificationCompat.FocusStatus {
        return when {
            event.success -> XiaomiFocusNotificationCompat.FocusStatus.SUCCESS
            event.message.contains("verify_failed", ignoreCase = true) ->
                XiaomiFocusNotificationCompat.FocusStatus.VERIFY_FAILED
            else -> XiaomiFocusNotificationCompat.FocusStatus.FAILED
        }
    }

    private fun buildDisplayTitle(
        simLabel: String,
        focusStatus: XiaomiFocusNotificationCompat.FocusStatus
    ): String {
        return when (focusStatus) {
            XiaomiFocusNotificationCompat.FocusStatus.SUCCESS ->
                context.getString(R.string.notify_focus_title_success, simLabel)
            XiaomiFocusNotificationCompat.FocusStatus.FAILED ->
                context.getString(R.string.notify_focus_title_failed, simLabel)
            XiaomiFocusNotificationCompat.FocusStatus.VERIFY_FAILED ->
                context.getString(R.string.notify_focus_title_verify_failed, simLabel)
        }
    }

    private fun buildDisplaySubtitle(focusStatus: XiaomiFocusNotificationCompat.FocusStatus): String {
        val status = when (focusStatus) {
            XiaomiFocusNotificationCompat.FocusStatus.SUCCESS ->
                context.getString(R.string.notify_focus_status_success)
            XiaomiFocusNotificationCompat.FocusStatus.FAILED ->
                context.getString(R.string.notify_focus_status_failed)
            XiaomiFocusNotificationCompat.FocusStatus.VERIFY_FAILED ->
                context.getString(R.string.notify_focus_status_verify_failed)
        }
        return "TrafficSIM \u00B7 $status"
    }

    private fun humanizeReasonId(raw: String): String {
        val normalized = raw.trim()
            .replace(Regex("[_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return context.getString(R.string.label_unknown)
        return normalized.split(' ').joinToString(" ") { part ->
            when {
                part.isBlank() -> part
                part.any { it.isUpperCase() } -> part
                part.all { it.isDigit() } -> part
                else -> part.lowercase(Locale.ROOT)
                    .replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) }
            }
        }
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
                AutoSwitchServiceChannels.FOCUS_CHANNEL_ID,
                context.getString(R.string.notify_channel_focus),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                AutoSwitchServiceChannels.EVENT_CHANNEL_ID,
                context.getString(R.string.notify_channel_events),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun scheduleTransition(
        context: Context,
        action: String,
        delayMs: Long,
        payload: EventPayload
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = buildTransitionPendingIntent(context, action, payload)
        val triggerAtMs = SystemClock.elapsedRealtime() + delayMs
        alarmManager.cancel(pendingIntent)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }

            else -> {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                )
            }
        }
    }

    private fun scheduleSummaryTransition(context: Context, payload: EventPayload) {
        scheduleInProcessSummary(context, payload)
        scheduleTransition(context, ACTION_POST_SUMMARY_FOCUS_NOTIFICATION, MAIN_FOCUS_DISPLAY_MS, payload)
    }

    private fun scheduleInProcessSummary(context: Context, payload: EventPayload) {
        val runnable = Runnable {
            SwitchEventNotifier(context.applicationContext).postSummaryNotification(
                payload = payload,
                source = "handler"
            )
        }
        synchronized(TRANSITION_LOCK) {
            summaryRunnable?.let(mainHandler::removeCallbacks)
            summaryRunnable = runnable
        }
        mainHandler.postDelayed(runnable, MAIN_FOCUS_DISPLAY_MS)
    }

    private fun payloadFromIntent(intent: Intent): EventPayload? {
        val token = intent.getStringExtra(EXTRA_EVENT_TOKEN) ?: return null
        val title = intent.getStringExtra(EXTRA_EVENT_TITLE) ?: return null
        val text = intent.getStringExtra(EXTRA_EVENT_TEXT) ?: return null
        val message = intent.getStringExtra(EXTRA_EVENT_MESSAGE).orEmpty()
        val status = intent.getStringExtra(EXTRA_EVENT_STATUS)
            ?.let { runCatching { XiaomiFocusNotificationCompat.FocusStatus.valueOf(it) }.getOrNull() }
            ?: return null
        return EventPayload(
            token = token,
            title = title,
            text = text,
            message = message,
            focusStatus = status
        )
    }

    private data class EventPayload(
        val token: String,
        val title: String,
        val text: String,
        val message: String,
        val focusStatus: XiaomiFocusNotificationCompat.FocusStatus
    )

    private enum class TransitionPhase {
        IDLE,
        MAIN,
        SUMMARY
    }

    companion object {
        const val ACTION_POST_SUMMARY_FOCUS_NOTIFICATION =
            "com.laros.lsp.traffics.action.POST_SUMMARY_FOCUS_NOTIFICATION"
        const val ACTION_DEBUG_FOCUS_NOTIFICATION =
            "com.laros.lsp.traffics.action.DEBUG_FOCUS_NOTIFICATION"
        const val EXTRA_DEBUG_STATUS = "tm.focus.debug_status"
        const val EXTRA_DEBUG_TARGET_SLOT = "tm.focus.debug_target_slot"
        const val EXTRA_DEBUG_REASON = "tm.focus.debug_reason"
        const val EXTRA_DEBUG_TRANSPORT = "tm.focus.debug_transport"
        const val EXTRA_DEBUG_MESSAGE = "tm.focus.debug_message"

        private const val TAG = "TmFocusNotify"
        private const val EXTRA_MIUI_FOCUS_PICS = "miui.focus.pics"
        private const val EXTRA_MIUI_FOCUS_PARAM = "miui.focus.param"
        private const val EXTRA_MIUI_FOCUS_PARAM_V2 = "miui.focus.param_v2"
        private const val EXTRA_FOCUS_NOTIFY_ROLE = "tm.focus.notify_role"
        private const val EXTRA_EVENT_TOKEN = "tm.focus.event_token"
        private const val EXTRA_EVENT_TITLE = "tm.focus.event_title"
        private const val EXTRA_EVENT_TEXT = "tm.focus.event_text"
        private const val EXTRA_EVENT_MESSAGE = "tm.focus.event_message"
        private const val EXTRA_EVENT_STATUS = "tm.focus.event_status"
        private const val DUPLICATE_EVENT_WINDOW_MS = 15_000L
        private const val MAIN_FOCUS_DISPLAY_MS = 3_000L
        private const val SUMMARY_REQUEST_CODE = 6101
        private const val PREFS_NAME = "tm_focus_notification"
        private const val PREF_ACTIVE_TOKEN = "active_token"
        private const val PREF_ACTIVE_PHASE = "active_phase"

        private var lastEventKey: String? = null
        private var lastEventAtMs: Long = 0L
        private val mainHandler = Handler(Looper.getMainLooper())
        private val TRANSITION_LOCK = Any()
        private var summaryRunnable: Runnable? = null

        fun cancelPendingTransitions(context: Context) {
            cancelSummaryTransition(context, clearPhase = true)
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(AutoSwitchServiceChannels.EVENT_TRAY_NOTIFY_ID)
        }

        private fun buildTransitionPendingIntent(
            context: Context,
            action: String,
            payload: EventPayload?
        ): PendingIntent {
            val requestCode = SUMMARY_REQUEST_CODE
            val intent = Intent(context, FocusSummaryReceiver::class.java).apply {
                this.action = action
                payload?.let {
                    putExtra(EXTRA_EVENT_TOKEN, it.token)
                    putExtra(EXTRA_EVENT_TITLE, it.title)
                    putExtra(EXTRA_EVENT_TEXT, it.text)
                    putExtra(EXTRA_EVENT_MESSAGE, it.message)
                    putExtra(EXTRA_EVENT_STATUS, it.focusStatus.name)
                }
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun cancelSummaryTransition(context: Context, clearPhase: Boolean) {
            synchronized(TRANSITION_LOCK) {
                summaryRunnable?.let(mainHandler::removeCallbacks)
                summaryRunnable = null
            }

            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val summaryIntent = buildTransitionPendingIntent(
                context,
                ACTION_POST_SUMMARY_FOCUS_NOTIFICATION,
                null
            )
            alarmManager.cancel(summaryIntent)
            summaryIntent.cancel()

            if (clearPhase) {
                clearActiveState(context)
            }
        }

        private fun advanceToSummary(context: Context, token: String): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            synchronized(TRANSITION_LOCK) {
                val activeToken = prefs.getString(PREF_ACTIVE_TOKEN, null)
                val activePhase = prefs.getString(PREF_ACTIVE_PHASE, TransitionPhase.IDLE.name)
                if (activeToken != token || activePhase != TransitionPhase.MAIN.name) {
                    return false
                }
                prefs.edit()
                    .putString(PREF_ACTIVE_PHASE, TransitionPhase.SUMMARY.name)
                    .apply()
                return true
            }
        }

        private fun saveActiveState(context: Context, token: String, phase: TransitionPhase) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_ACTIVE_TOKEN, token)
                .putString(PREF_ACTIVE_PHASE, phase.name)
                .apply()
        }

        private fun clearActiveState(context: Context) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_ACTIVE_TOKEN)
                .putString(PREF_ACTIVE_PHASE, TransitionPhase.IDLE.name)
                .apply()
        }
    }
}
