package com.odoocompanion.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPlanTest {
    private val found = PushKeyLookup.Found("BKey", registered = false)

    @Test
    fun `an enrolled handset registers through the distributor it has`() {
        assertEquals(
            PushPlan.Register("io.heckel.ntfy", "BKey"),
            PushPlan.decide(true, found, saved = null, installed = listOf("io.heckel.ntfy")),
        )
    }

    @Test
    fun `the distributor already chosen is kept while installed`() {
        assertEquals(
            PushPlan.Register("org.b", "BKey"),
            PushPlan.decide(true, found, saved = "org.b", installed = listOf("org.a", "org.b")),
        )
        assertEquals(
            PushPlan.Register("org.a", "BKey"),
            PushPlan.decide(true, found, saved = "org.gone", installed = listOf("org.b", "org.a")),
        )
    }

    @Test
    fun `without a distributor the handset keeps its schedule`() {
        assertEquals(PushPlan.NoDistributor, PushPlan.decide(true, found, null, emptyList()))
    }

    @Test
    fun `an odoo out of reach changes nothing`() {
        assertEquals(
            PushPlan.Keep,
            PushPlan.decide(true, PushKeyLookup.Unavailable("down"), "org.a", listOf("org.a")),
        )
    }

    @Test
    fun `an odoo that sends no push and a handset not enrolled take no wake-ups`() {
        assertEquals(
            PushPlan.Unregister,
            PushPlan.decide(true, PushKeyLookup.Off, null, listOf("a")),
        )
        assertEquals(PushPlan.Unregister, PushPlan.decide(false, found, null, listOf("a")))
    }

    @Test
    fun `a wake-up names what it asks for`() {
        assertEquals(PushCommand.SYNC, PushCommand.parse("""{"command":"sync"}""".toByteArray()))
        assertEquals(
            PushCommand.SOFTPHONE,
            PushCommand.parse("""{"command":"softphone","extra":1}""".toByteArray()),
        )
    }

    @Test
    fun `a wake-up this build does not know is no command`() {
        assertNull(PushCommand.parse("""{"command":"wipe"}""".toByteArray()))
        assertNull(PushCommand.parse("""{"command":["sync"]}""".toByteArray()))
        assertNull(PushCommand.parse("not json".toByteArray()))
    }

    @Test
    fun `a registration never prints its keys`() {
        assertEquals(
            "PushRegistration(endpoint=https://push.example/x)",
            PushRegistration("https://push.example/x", "p256dh-secret", "auth-secret").toString(),
        )
    }
}
