package com.jay.phonemic

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Small home-screen button: tap = start / stop the mic. Same colours as the PC app. */
class MicWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(context, MicWidget::class.java))
                if (ids.isEmpty()) return

                val bg: Int
                val icon: Int
                val label: String
                when {
                    !MicState.running -> {
                        bg = R.drawable.widget_bg_red; icon = R.drawable.ic_stat_mic_none; label = "OFF"
                    }
                    MicState.mode() == "live" -> {
                        bg = R.drawable.widget_bg_green; icon = R.drawable.ic_stat_mic; label = "LIVE"
                    }
                    MicState.mode() == "muted" -> {
                        bg = R.drawable.widget_bg_gray; icon = R.drawable.ic_stat_mic_off; label = "MUTED"
                    }
                    else -> {
                        bg = R.drawable.widget_bg_red; icon = R.drawable.ic_stat_mic_none; label = "WAIT"
                    }
                }

                val rv = RemoteViews(context.packageName, R.layout.widget_mic)
                rv.setImageViewResource(R.id.widget_bg, bg)
                rv.setImageViewResource(R.id.widget_icon, icon)
                rv.setTextViewText(R.id.widget_label, label)
                val pi = PendingIntent.getActivity(
                    context, 10,
                    Intent(context, ToggleActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                rv.setOnClickPendingIntent(R.id.widget_root, pi)
                mgr.updateAppWidget(ids, rv)
            } catch (_: Exception) {
            }
        }
    }
}
