package com.odoocompanion.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
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
        creatorAllowsStart(),
    )

    private fun creatorAllowsStart(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
            .toBundle()
    }

    private const val REQUEST_CODE = 7_302
}

class ScanWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        refresh(context, manager, ids)

    override fun onEnabled(context: Context) = refresh(context)

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) = refresh(context, manager, intArrayOf(id))

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) = refresh(context)

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ScanWidget::class.java))
            if (ids.isNotEmpty()) refresh(context, manager, ids)
        }

        private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_scan).apply {
                setOnClickPendingIntent(R.id.widgetScan, ScanShortcut.pendingIntent(context))
            }
            try {
                manager.updateAppWidget(ids, views)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not wire the Scan widget", e)
            }
        }

        private const val TAG = "ScanWidget"
    }
}
