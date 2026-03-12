package com.laros.lsp.traffics.core

import android.app.Notification
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.laros.lsp.traffics.R
import org.json.JSONObject

object XiaomiFocusNotificationCompat {
    private const val EXTRA_MIUI_FOCUS_PARAM = "miui.focus.param"
    private const val EXTRA_MIUI_FOCUS_PARAM_V2 = "miui.focus.param_v2"
    private const val EXTRA_MIUI_FOCUS_PICS = "miui.focus.pics"

    private const val BUSINESS_SCENE = "traffic_manager"
    private const val KEY_FOCUS_MAIN_PIC = "miui.focus.pic_tm_island"
    private const val KEY_FOCUS_SMALL_PIC = "miui.focus.pic_tm_small"
    private const val MAIN_EXPANDED_TIME_SEC = 3
    private const val MAIN_ISLAND_TIMEOUT_SEC = 8
    private const val SUMMARY_ISLAND_TIMEOUT_SEC = 5
    private const val FOCUS_TIMEOUT = 720
    private const val IMAGE_TEXT_TYPE_PLAIN_ICON = 1
    private const val IMAGE_TEXT_TYPE_RIGHT_ICON = 2

    enum class FocusStatus {
        SUCCESS,
        FAILED,
        VERIFY_FAILED
    }

    enum class FocusMode {
        MAIN,
        SUMMARY
    }

    fun applyToEventNotification(
        context: Context,
        builder: NotificationCompat.Builder,
        notifyId: String,
        focusTitle: String,
        focusSubtitle: String,
        focusStatus: FocusStatus,
        focusMode: FocusMode
    ): Notification {
        val focusPics = buildFocusPicsBundle(context, focusStatus, focusMode)
        val paramValue = runCatching {
            buildFocusParam(
                notifyId = notifyId,
                title = focusTitle,
                subtitle = focusSubtitle,
                focusMode = focusMode
            ).toString()
        }.getOrNull() ?: return builder.build()

        builder.addExtras(
            Bundle().apply {
                putBundle(EXTRA_MIUI_FOCUS_PICS, focusPics)
                putString(EXTRA_MIUI_FOCUS_PARAM, paramValue)
                putString(EXTRA_MIUI_FOCUS_PARAM_V2, paramValue)
            }
        )
        val notification = builder.build()
        val extras = notification.extras ?: Bundle().also { notification.extras = it }
        extras.putBundle(EXTRA_MIUI_FOCUS_PICS, focusPics)
        extras.putString(EXTRA_MIUI_FOCUS_PARAM, paramValue)
        extras.putString(EXTRA_MIUI_FOCUS_PARAM_V2, paramValue)
        return notification
    }

    private fun buildFocusParam(
        notifyId: String,
        title: String,
        subtitle: String,
        focusMode: FocusMode
    ): JSONObject {
        val summaryMode = focusMode == FocusMode.SUMMARY
        val islandTimeoutSec = if (summaryMode) SUMMARY_ISLAND_TIMEOUT_SEC else MAIN_ISLAND_TIMEOUT_SEC
        val paramIsland = JSONObject().apply {
            put("islandProperty", 1)
            put("islandPriority", 1)
            put("islandOrder", true)
            put("islandTimeout", islandTimeoutSec)
            put("expandedTime", if (summaryMode) 0 else MAIN_EXPANDED_TIME_SEC)
            put("dismissIsland", false)
            put("bigIslandArea", JSONObject().apply {
                put("imageTextInfoLeft", buildBigIslandLeft(title, subtitle, summaryMode))
                put("imageTextInfoRight", buildBigIslandRight())
            })
            put("smallIslandArea", buildSmallIslandArea())
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("notifyId", notifyId)
            put("business", BUSINESS_SCENE)
            put("islandFirstFloat", !summaryMode)
            put("enableFloat", true)
            put("filterWhenNoPermission", false)
            put("reopen", "reopen")
            put("ticker", title.take(32))
            put("tickerPic", KEY_FOCUS_SMALL_PIC)
            put("tickerPicDark", KEY_FOCUS_SMALL_PIC)
            put("aodTitle", title.take(32))
            put("chatInfo", JSONObject().apply {
                put("picProfile", KEY_FOCUS_MAIN_PIC)
                put("picProfileDark", KEY_FOCUS_MAIN_PIC)
                put("title", title.take(32))
                put("content", subtitle.take(32))
            })
            put("param_island", paramIsland)
            put("timeout", FOCUS_TIMEOUT)
            put("updatable", true)
        }

        return JSONObject().apply { put("param_v2", paramV2) }
    }

    private fun buildBigIslandLeft(title: String, subtitle: String, summaryMode: Boolean): JSONObject {
        val picKey = if (summaryMode) KEY_FOCUS_SMALL_PIC else KEY_FOCUS_MAIN_PIC
        val displayTitle = if (summaryMode) "" else title.take(14)
        val displaySubtitle = if (summaryMode) "" else subtitle.take(14)
        return JSONObject().apply {
            put("type", 5)
            put("picInfo", buildFocusBadgePicInfo(picKey))
            put("textInfo", JSONObject().apply {
                put("title", displayTitle)
                put("content", displaySubtitle)
                put("showHighlightColor", false)
                put("narrowFont", false)
            })
        }
    }

    private fun buildSmallIslandArea(): JSONObject {
        return JSONObject().apply {
            put("type", IMAGE_TEXT_TYPE_PLAIN_ICON)
            put("picInfo", buildFocusPlainPicInfo(KEY_FOCUS_SMALL_PIC))
        }
    }

    private fun buildBigIslandRight(): JSONObject {
        return JSONObject().apply {
            put("type", IMAGE_TEXT_TYPE_RIGHT_ICON)
            put("picInfo", buildFocusPlainPicInfo(KEY_FOCUS_SMALL_PIC))
        }
    }

    private fun buildFocusPlainPicInfo(picKey: String): JSONObject {
        return JSONObject().apply {
            put("type", 1)
            put("pic", picKey)
        }
    }

    private fun buildFocusBadgePicInfo(picKey: String): JSONObject {
        return JSONObject().apply {
            put("type", 4)
            put("pic", picKey)
        }
    }

    private fun buildFocusPicsBundle(
        context: Context,
        focusStatus: FocusStatus,
        focusMode: FocusMode
    ): Bundle {
        val smallRes = resolveSmallIconRes(focusStatus)
        val mainRes = if (focusMode == FocusMode.SUMMARY) smallRes else R.drawable.tm_focus_inform_left
        val mainIcon = buildIcon(context, mainRes)
        val smallIcon = buildIcon(context, smallRes)
        return Bundle().apply {
            putParcelable(KEY_FOCUS_MAIN_PIC, mainIcon)
            putParcelable(KEY_FOCUS_SMALL_PIC, smallIcon)
        }
    }

    private fun buildIcon(context: Context, resId: Int): Icon {
        val bitmap = BitmapFactory.decodeResource(context.resources, resId)
        return if (bitmap != null) {
            Icon.createWithBitmap(bitmap)
        } else {
            Icon.createWithResource(context, resId)
        }
    }

    private fun resolveSmallIconRes(focusStatus: FocusStatus): Int {
        return when (focusStatus) {
            FocusStatus.SUCCESS -> R.drawable.tm_focus_status_success
            FocusStatus.FAILED, FocusStatus.VERIFY_FAILED -> R.drawable.tm_focus_status_failed
        }
    }
}
