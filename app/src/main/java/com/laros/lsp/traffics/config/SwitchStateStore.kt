package com.laros.lsp.traffics.config

import android.content.Context

class SwitchStateStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastSwitchAtMs(): Long = prefs.getLong(KEY_LAST_SWITCH_AT, 0L)

    fun setLastSwitchAtMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_SWITCH_AT, value).apply()
    }

    fun getSessionState(): SessionState {
        val activeRuleId = prefs.getString(KEY_ACTIVE_RULE_ID, null)
        val lastMatchedAtMs = prefs.getLong(KEY_LAST_MATCHED_AT, 0L)
        val lastSwitchAtMs = prefs.getLong(KEY_LAST_SWITCH_AT, 0L)
        val previousSlot = prefs.getInt(KEY_PREVIOUS_SLOT, -1).takeIf { it >= 0 }
        val noMatchTicks = prefs.getInt(KEY_NO_MATCH_TICKS, 0)
        val noWifiTicks = prefs.getInt(KEY_NO_WIFI_TICKS, 0)
        val lastNoWifiAtMs = prefs.getLong(KEY_LAST_NO_WIFI_AT, 0L)
        val latestActivity = maxOf(lastMatchedAtMs, lastNoWifiAtMs, lastSwitchAtMs)
        if (latestActivity > 0L &&
            System.currentTimeMillis() - latestActivity > SESSION_TTL_MS
        ) {
            clearSessionStateInternal()
            return SessionState(
                activeRuleId = null,
                lastMatchedAtMs = 0L,
                lastSwitchAtMs = lastSwitchAtMs,
                previousSlotBeforeRule = null,
                consecutiveNoMatchTicks = 0,
                consecutiveNoWifiTicks = 0,
                lastNoWifiAtMs = 0L
            )
        }
        return SessionState(
            activeRuleId = activeRuleId,
            lastMatchedAtMs = lastMatchedAtMs,
            lastSwitchAtMs = lastSwitchAtMs,
            previousSlotBeforeRule = previousSlot,
            consecutiveNoMatchTicks = noMatchTicks,
            consecutiveNoWifiTicks = noWifiTicks,
            lastNoWifiAtMs = lastNoWifiAtMs
        )
    }

    fun setSessionState(state: SessionState) {
        prefs.edit()
            .putString(KEY_ACTIVE_RULE_ID, state.activeRuleId)
            .putLong(KEY_LAST_MATCHED_AT, state.lastMatchedAtMs)
            .putLong(KEY_LAST_SWITCH_AT, state.lastSwitchAtMs)
            .putInt(KEY_PREVIOUS_SLOT, state.previousSlotBeforeRule ?: -1)
            .putInt(KEY_NO_MATCH_TICKS, state.consecutiveNoMatchTicks)
            .putInt(KEY_NO_WIFI_TICKS, state.consecutiveNoWifiTicks)
            .putLong(KEY_LAST_NO_WIFI_AT, state.lastNoWifiAtMs)
            .apply()
    }

    fun getLastSwitchEvent(): LastSwitchEvent? {
        val atMs = prefs.getLong(KEY_LAST_EVENT_AT, 0L)
        if (atMs <= 0L) return null
        val success = prefs.getBoolean(KEY_LAST_EVENT_SUCCESS, false)
        val targetSlot = prefs.getInt(KEY_LAST_EVENT_TARGET_SLOT, -1)
        val reason = prefs.getString(KEY_LAST_EVENT_REASON, "") ?: ""
        val transport = prefs.getString(KEY_LAST_EVENT_TRANSPORT, "") ?: ""
        val message = prefs.getString(KEY_LAST_EVENT_MESSAGE, "") ?: ""
        return LastSwitchEvent(
            atMs = atMs,
            success = success,
            targetSlot = targetSlot.coerceAtLeast(0),
            reason = reason,
            transport = transport,
            message = message
        )
    }

    fun setLastSwitchEvent(event: LastSwitchEvent) {
        val msg = event.message.take(400)
        prefs.edit()
            .putLong(KEY_LAST_EVENT_AT, event.atMs)
            .putBoolean(KEY_LAST_EVENT_SUCCESS, event.success)
            .putInt(KEY_LAST_EVENT_TARGET_SLOT, event.targetSlot)
            .putString(KEY_LAST_EVENT_REASON, event.reason)
            .putString(KEY_LAST_EVENT_TRANSPORT, event.transport)
            .putString(KEY_LAST_EVENT_MESSAGE, msg)
            .apply()
    }

    private fun clearSessionStateInternal() {
        prefs.edit()
            .remove(KEY_ACTIVE_RULE_ID)
            .remove(KEY_LAST_MATCHED_AT)
            .remove(KEY_PREVIOUS_SLOT)
            .remove(KEY_NO_MATCH_TICKS)
            .remove(KEY_NO_WIFI_TICKS)
            .remove(KEY_LAST_NO_WIFI_AT)
            .apply()
    }

    data class SessionState(
        val activeRuleId: String?,
        val lastMatchedAtMs: Long,
        val lastSwitchAtMs: Long,
        val previousSlotBeforeRule: Int?,
        val consecutiveNoMatchTicks: Int,
        val consecutiveNoWifiTicks: Int,
        val lastNoWifiAtMs: Long
    )

    data class LastSwitchEvent(
        val atMs: Long,
        val success: Boolean,
        val targetSlot: Int,
        val reason: String,
        val transport: String,
        val message: String
    )

    companion object {
        private const val PREFS_NAME = "traffic_manager_state"
        private const val KEY_LAST_SWITCH_AT = "last_switch_at_ms"
        private const val KEY_ACTIVE_RULE_ID = "active_rule_id"
        private const val KEY_LAST_MATCHED_AT = "last_matched_at_ms"
        private const val KEY_PREVIOUS_SLOT = "previous_slot_before_rule"
        private const val KEY_NO_MATCH_TICKS = "consecutive_no_match_ticks"
        private const val KEY_NO_WIFI_TICKS = "consecutive_no_wifi_ticks"
        private const val KEY_LAST_NO_WIFI_AT = "last_no_wifi_at_ms"
        private const val KEY_LAST_EVENT_AT = "last_event_at_ms"
        private const val KEY_LAST_EVENT_SUCCESS = "last_event_success"
        private const val KEY_LAST_EVENT_TARGET_SLOT = "last_event_target_slot"
        private const val KEY_LAST_EVENT_REASON = "last_event_reason"
        private const val KEY_LAST_EVENT_TRANSPORT = "last_event_transport"
        private const val KEY_LAST_EVENT_MESSAGE = "last_event_message"
        private const val SESSION_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
