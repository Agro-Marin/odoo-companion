package com.odoocompanion.ui

import com.odoocompanion.config.ManagedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentGuideTest {
    private val empty = GuideField.entries.associateWith { "" }

    @Test
    fun `a fresh install is guided through all three fields in order`() {
        assertEquals(GuideField.entries, EnrollmentGuide.pending(empty, emptySet()))
        assertEquals(
            GuideStep(GuideField.BASE_URL, 1, 3),
            EnrollmentGuide.current(empty, emptySet(), emptySet()),
        )
    }

    @Test
    fun `a field emptied later is the only step`() {
        val values = mapOf(
            GuideField.BASE_URL to "https://odoo.example.com",
            GuideField.IDENTIFIER to "phone-01",
            GuideField.TOKEN to " ",
        )
        assertEquals(
            GuideStep(GuideField.TOKEN, 1, 1),
            EnrollmentGuide.current(values, emptySet(), emptySet()),
        )
    }

    @Test
    fun `a field the policy manages is never asked for`() {
        val governed = setOf(ManagedKey.BASE_URL, ManagedKey.TOKEN)
        assertEquals(
            GuideStep(GuideField.IDENTIFIER, 1, 1),
            EnrollmentGuide.current(empty, governed, emptySet()),
        )
    }

    @Test
    fun `a dismissed step gives way to the next one, still counted`() {
        assertEquals(
            GuideStep(GuideField.IDENTIFIER, 2, 3),
            EnrollmentGuide.current(empty, emptySet(), setOf(GuideField.BASE_URL)),
        )
        assertNull(EnrollmentGuide.current(empty, emptySet(), GuideField.entries.toSet()))
    }

    @Test
    fun `an enrolled phone has no step`() {
        val values = GuideField.entries.associateWith { "x" }
        assertNull(EnrollmentGuide.current(values, emptySet(), emptySet()))
    }
}
