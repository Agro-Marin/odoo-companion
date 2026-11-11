package com.odoocompanion.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.odoocompanion.R

object ScanShortcut {
    const val ACTION_SCAN = "com.odoocompanion.action.SCAN"

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_SCAN)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val REQUEST_CODE = 7_302
}

class ScanWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_scan).apply {
            setOnClickPendingIntent(R.id.widgetScan, ScanShortcut.pendingIntent(context))
        }
        manager.updateAppWidget(ids, views)
    }
}
