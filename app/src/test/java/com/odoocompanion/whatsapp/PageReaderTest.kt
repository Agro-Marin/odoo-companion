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
    fun `even one column of an unusually tall page fits the pixel budget`() {
        val (width, height) = PageReader.bitmapSize(1, Int.MAX_VALUE)
        assertEquals(1, width)
        assertTrue(height in 1..PageReader.MAX_PIXELS.toInt())
    }

    @Test
    fun `page sizes at rounding boundaries respect both bitmap limits`() {
        val sizes = listOf(
            Int.MIN_VALUE, 0, 1, 2, 595, 1599, 1600, 1601, 2812, 2813, 4_500_001, Int.MAX_VALUE,
        )
        for (pageWidth in sizes) {
            for (pageHeight in sizes) {
                val (w, h) = PageReader.bitmapSize(pageWidth, pageHeight)
                assertTrue(
                    "$pageWidth x $pageHeight -> $w x $h",
                    w in 1..PageReader.TARGET_WIDTH &&
                        h > 0 && w.toLong() * h <= PageReader.MAX_PIXELS
                )
            }
        }
    }

    @Test
    fun `a page reporting no size still yields a bitmap`() {
        val (width, height) = PageReader.bitmapSize(0, 0)

        assertTrue(width >= 1 && height >= 1)
    }
}
