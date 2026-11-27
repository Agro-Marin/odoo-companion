package com.odoocompanion.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class ScanWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [29, 31], application = Application::class)
    fun `the launcher receives the appropriate scan preview for its Android version`() {
        val android = "http://schemas.android.com/apk/res/android"
        context.resources.getXml(R.xml.widget_scan_info).use { xml ->
            while (xml.eventType == XmlPullParser.START_DOCUMENT) xml.next()
            assertEquals(XmlPullParser.START_TAG, xml.eventType)
            assertEquals(
                R.drawable.ic_scan_badge,
                xml.getAttributeResourceValue(android, "previewImage", 0)
            )
            val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val layout = xml.getAttributeResourceValue(android, "previewLayout", 0)
            assertEquals(if (modern) R.layout.widget_scan else 0, layout)
            assertEquals(
                if (modern) 2 else 0,
                xml.getAttributeIntValue(android, "targetCellWidth", 0)
            )
            assertEquals(
                if (modern) 1 else 0,
                xml.getAttributeIntValue(android, "targetCellHeight", 0)
            )
        }
    }

    @Test
    fun `a widget the app wires itself opens the scanner, with no update from the launcher`() {
        val manager = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        val id = manager.createWidget(ScanWidget::class.java, R.layout.widget_scan)

        ScanWidget.refresh(context)
        manager.getViewFor(id).findViewById<View>(R.id.widgetScan).performClick()

        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals(ScanShortcut.ACTION_SCAN, started.action)
        assertEquals(MainActivity::class.java.name, started.component?.className)
    }
}
