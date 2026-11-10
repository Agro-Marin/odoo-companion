package com.odoocompanion.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReaderTest {
    @Test
    fun `an ordinary page is drawn at the width OCR reads`() {
        val (width, height) = PageReader.bitmapSize(595, 842)

        assertEquals(PageReader.TARGET_WIDTH, width)
        assertEquals(2264, height)
    }

    @Test
    fun `a receipt-shaped page is drawn within the pixel budget`() {
        val (width, height) = PageReader.bitmapSize(72, 14_400)

        assertTrue("$width x $height", width.toLong() * height <= PageReader.MAX_PIXELS)
        assertEquals(200.0, height.toDouble() / width, 1.0)
    }

    @Test
    fun `a page reporting no size still yields a bitmap`() {
        val (width, height) = PageReader.bitmapSize(0, 0)

        assertTrue(width >= 1 && height >= 1)
    }
}
