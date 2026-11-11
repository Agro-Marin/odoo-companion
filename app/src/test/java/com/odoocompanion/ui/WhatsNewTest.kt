package com.odoocompanion.ui

import com.odoocompanion.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test
    fun `a phone updated past a release sees that release's pages`() {
        val pages = WhatsNew.pagesSince(seen = 7, installed = 8)

        assertEquals(WhatsNew.RELEASES.getValue(8), pages)
    }

    @Test
    fun `nothing is shown again once the installed version was seen`() {
        assertTrue(WhatsNew.pagesSince(seen = 8, installed = 8).isEmpty())
    }

    @Test
    fun `a fresh install sees every release up to its own`() {
        val pages = WhatsNew.pagesSince(seen = 0, installed = 8)

        assertEquals(WhatsNew.RELEASES.filterKeys { it <= 8 }.values.flatten(), pages)
    }

    @Test
    fun `download progress is a whole percent within bounds`() {
        assertEquals(0, Updater.percentOf(0, 0))
        assertEquals(45, Updater.percentOf(45, 100))
        assertEquals(100, Updater.percentOf(120, 100))
    }
}
