package com.odoocompanion.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ScanWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a widget the app wires itself opens the scanner, with no update from the launcher`() {
        val manager = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        val id = manager.createWidget(ScanWidget::class.java, R.layout.widget_scan)

        ScanWidget.refresh(context)
        manager.getViewFor(id).findViewById<View>(R.id.widgetScan).performClick()

        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(ScanShortcut.ACTION_SCAN, started.action)
        assertEquals(MainActivity::class.java.name, started.component?.className)
    }
}
