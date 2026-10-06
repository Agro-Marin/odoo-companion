package com.odoocompanion.softphone

import android.telecom.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Call
import org.linphone.core.Reason

class SoftphoneCallBookTest {
    private class Line(val name: String) {
        override fun toString() = name
    }

    private val book = SoftphoneCallBook<Line>()
    private val first = Line("first")

    private fun ringing(callId: String = "call-1", line: Line = first) {
        assertEquals(Offer.REPORT, book.offer(callId))
        assertTrue(book.attachIncoming(callId, line))
    }

    @Test
    fun `a second call declined busy does not end the first`() {
        ringing()

        assertEquals(Offer.DECLINE_BUSY, book.offer("call-2"))
        // the SIP stack reports the declined call's End and Released
        assertNull(book.ended("call-2"))
        assertNull(book.ended("call-2"))

        assertSame(first, book.connectionOf("call-1"))
        assertSame(first, book.current)
        assertEquals(Ended(first), book.ended("call-1"))
        assertTrue(book.idle)
    }

    @Test
    fun `a second invite before telecom created the first's connection is declined busy`() {
        assertEquals(Offer.REPORT, book.offer("call-1"))

        assertEquals(Offer.DECLINE_BUSY, book.offer("call-2"))
        assertNull(book.ended("call-2"))

        assertTrue(book.attachIncoming("call-1", first))
        assertSame(first, book.connectionOf("call-1"))
    }

    @Test
    fun `a caller who hangs up before telecom creates the connection gets none`() {
        assertEquals(Offer.REPORT, book.offer("call-1"))

        assertEquals(Ended<Line>(null), book.ended("call-1"))

        assertFalse(book.attachIncoming("call-1", first))
        assertTrue(book.idle)
        assertEquals(Offer.REPORT, book.offer("call-2"))
    }

    @Test
    fun `telecom's connection belongs to the call it was created for`() {
        assertEquals(Offer.REPORT, book.offer("call-1"))

        assertFalse("a stale request", book.attachIncoming("call-0", first))
        assertTrue("a request without its Call-ID", book.attachIncoming(null, first))
        assertEquals("call-1", book.callOf(first))
    }

    @Test
    fun `a call telecom refused is the one declined, and frees the line`() {
        assertEquals(Offer.REPORT, book.offer("call-1"))

        assertNull(book.refuseIncoming("call-0"))
        assertEquals("call-1", book.refuseIncoming("call-1"))
        assertNull("declined once", book.refuseIncoming(null))
        assertTrue(book.idle)
    }

    @Test
    fun `an outgoing call is keyed like an incoming one`() {
        val out = Line("out")
        book.attachOutgoing("out-1", out)

        assertEquals(Offer.DECLINE_BUSY, book.offer("call-1"))
        assertSame(out, book.connectionOf("out-1"))
        assertEquals(Ended(out), book.ended("out-1"))
        assertTrue(book.idle)
    }

    @Test
    fun `a stack going away hands back every open connection`() {
        ringing()

        assertEquals(listOf(first), book.clear())
        assertTrue(book.idle)
        assertNull(book.ended("call-1"))
    }

    @Test
    fun `the microphone is open only once a call is answered or dialled`() {
        assertFalse(SoftphoneCallBook.onTheLine(emptyList()))
        assertFalse(SoftphoneCallBook.onTheLine(listOf(Call.State.IncomingReceived)))
        assertFalse(SoftphoneCallBook.onTheLine(listOf(Call.State.End, Call.State.Released)))
        assertTrue(SoftphoneCallBook.onTheLine(listOf(Call.State.StreamsRunning)))
        assertTrue(SoftphoneCallBook.onTheLine(listOf(Call.State.OutgoingInit)))
        assertTrue(SoftphoneCallBook.onTheLine(listOf(Call.State.Paused)))
        assertTrue(
            "a second call ringing in does not close the first",
            SoftphoneCallBook.onTheLine(listOf(Call.State.StreamsRunning, Call.State.End)),
        )
    }

    @Test
    fun `why a call ended, as telecom names it`() {
        fun cause(state: Call.State, status: Call.Status?, reason: Reason? = Reason.None) =
            SoftphoneCallBook.disconnectCause(state, status, reason)

        assertEquals(
            "answered in the browser: a CANCEL with Reason 200",
            DisconnectCause.ANSWERED_ELSEWHERE,
            cause(Call.State.End, Call.Status.AcceptedElsewhere),
        )
        assertEquals(DisconnectCause.REJECTED, cause(Call.State.End, Call.Status.DeclinedElsewhere))
        assertEquals(DisconnectCause.MISSED, cause(Call.State.End, Call.Status.Missed))
        assertEquals(
            DisconnectCause.BUSY,
            cause(Call.State.Error, Call.Status.Aborted, Reason.Busy),
        )
        assertEquals(
            DisconnectCause.REMOTE,
            cause(Call.State.Error, Call.Status.Declined, Reason.Declined),
        )
        assertEquals(
            DisconnectCause.ERROR,
            cause(Call.State.Error, Call.Status.Aborted, Reason.IOError),
        )
        assertEquals(DisconnectCause.REMOTE, cause(Call.State.End, Call.Status.Success))
        assertEquals(DisconnectCause.REMOTE, cause(Call.State.Released, null, null))
    }
}
