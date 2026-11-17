package com.odoocompanion.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPreviewTest {
    @Test
    fun `a large page is brought within the longest side, keeping its shape`() {
        assertEquals(960 to 1280, ScanPreview.fitted(3000, 4000))
        assertEquals(1280 to 720, ScanPreview.fitted(1920, 1080))
    }

    @Test
    fun `a small picture is never enlarged`() {
        assertEquals(200 to 300, ScanPreview.fitted(200, 300))
    }
}
