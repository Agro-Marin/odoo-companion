package com.odoocompanion.softphone

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftphonePlanTest {
    private val named = SoftphoneSettings("201", "new", "erp.agromarin.mx")
    private val stored = SoftphoneSettings("201", "old", "erp.agromarin.mx")

    @Test
    fun `an enrolled handset runs the extension Odoo names`() {
        assertEquals(
            SoftphonePlan.Run(named),
            SoftphonePlan.decide(true, SoftphoneLookup.Found(named), stored),
        )
    }

    @Test
    fun `odoo out of reach keeps the last extension ringing`() {
        assertEquals(
            SoftphonePlan.Run(stored),
            SoftphonePlan.decide(true, SoftphoneLookup.Unavailable("offline"), stored),
        )
        assertEquals(
            SoftphonePlan.Stop,
            SoftphonePlan.decide(true, SoftphoneLookup.Unavailable("offline"), null),
        )
    }

    @Test
    fun `a user without an extension, or a handset not enrolled, stops`() {
        assertEquals(
            SoftphonePlan.Stop,
            SoftphonePlan.decide(true, SoftphoneLookup.NoExtension, stored)
        )
        assertEquals(
            SoftphonePlan.Stop,
            SoftphonePlan.decide(false, SoftphoneLookup.Found(named), stored)
        )
    }

    @Test
    fun `a token odoo refuses stops the extension and forgets it`() {
        assertEquals(
            SoftphonePlan.Stop,
            SoftphonePlan.decide(true, SoftphoneLookup.Refused(401), stored),
        )
        assertEquals(
            SoftphonePlan.Stop,
            SoftphonePlan.decide(true, SoftphoneLookup.Refused(403), stored),
        )
    }
}
