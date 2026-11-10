package com.odoocompanion.sync

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.DeviceConfig
import com.odoocompanion.config.ManagedValues
import com.odoocompanion.config.Settings
import com.odoocompanion.config.enrol
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.location.LocationQueue
import com.odoocompanion.net.CallRecord
import com.odoocompanion.net.LocationFix
import com.odoocompanion.net.OdooClient
import com.odoocompanion.net.WireJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// A row goes only to the server and device record it was collected for. A
// handset moved to another Odoo -- or to another device record on the same one
// -- used to send the old enrolment's calls, recordings and positions to the
// new one, under the new device.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnrollmentHoldTest {
    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        database.close()
    }

    private fun settings(identifier: String = CURRENT_DEVICE) = Settings(
        baseUrl = server.url("/").toString().trimEnd('/'),
        identifier = identifier,
        token = "t",
    )

    private val current get() = settings().enrollment!!

    private val previous get() = settings(PREVIOUS_DEVICE).enrollment!!

    private fun drainer(now: Long = NOW) = OutboxDrainer(dao, OdooClient(), { now }) { settings() }

    private suspend fun queueCall(enrollment: String?, createdAt: Long = NOW, at: Long = 1L) =
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = WireJson.encodeToString(CallRecord("+5215512345678", "incoming", at, 9)),
                createdAt = createdAt,
                enrollment = enrollment,
            ),
        )

    private suspend fun queuePosition(enrollment: String?, createdAt: Long = NOW) = dao.insert(
        OutboxEntry(
            kind = OutboxKind.LOCATION,
            payload = WireJson.encodeToString(LocationFix(19.4, -99.1, createdAt)),
            createdAt = createdAt,
            enrollment = enrollment,
        ),
    )

    private fun accept(accepted: Int) = server.enqueue(
        MockResponse.Builder().code(200)
            .body("""{"service":"remote_mobile","status":"success","accepted":$accepted}""")
            .build(),
    )

    @Test
    fun `a previous enrolment's rows are held, not sent, and ask for no further pass`() = runTest {
        queueCall(previous)
        queuePosition(previous)

        val report = drainer().drainAll()

        assertEquals("nothing of the old device reached this one", 0, server.requestCount)
        assertFalse(
            "held rows are not work: they chained a drain after every drain",
            report.moreWorkPending
        )
        val counts = dao.counts(current).first()
        assertEquals(2, counts.held)
        assertEquals(previous, counts.heldFor)
        assertEquals("and they are not this enrolment's queue", 0, counts.calls + counts.positions)
    }

    @Test
    fun `a row stamped for this enrolment, or not stamped at all, is sent`() = runTest {
        queueCall(current, at = 1L)
        queueCall(null, at = 2L)
        accept(2)

        drainer().drainAll()

        assertEquals(1, server.requestCount)
        assertEquals(0, dao.counts(current).first().calls)
    }

    @Test
    fun `sent here, the held rows go to this server on the next drain`() = runTest {
        queueCall(previous)
        dao.adoptHeld(current, previous)
        accept(1)

        drainer().drainAll()

        assertEquals(1, server.requestCount)
        assertEquals(0, dao.counts(current).first().held)
    }

    @Test
    fun `discarded, positions go and the rest is withdrawn, never undeliverable`() = runTest {
        queueCall(previous)
        queuePosition(previous)

        dao.deleteHeldPositions(current, previous)
        dao.withdrawHeld(current, previous, NOW)
        drainer().drainAll()

        assertEquals(0, server.requestCount)
        val counts = dao.counts(current).first()
        assertEquals(0, counts.held)
        assertEquals("no operator action brings it back", 0, counts.undeliverable)
        assertEquals("the call is kept, set aside, until the purge", 1, dao.countDead())
        assertEquals(0, dao.take(OutboxKind.LOCATION, 10, enrollment = previous).size)
    }

    @Test
    fun `a handset nobody looks at withdraws what it held for the retention`() = runTest {
        val old = NOW - OutboxLimits.DEAD_RETENTION_MILLIS - 1
        queueCall(previous, createdAt = old, at = 1L)
        queuePosition(previous, createdAt = old)
        queueCall(previous, createdAt = NOW, at = 2L)

        val report = drainer().drainAll()

        assertEquals(0, server.requestCount)
        assertEquals("the old position went as positions go", 1, report.discarded)
        assertEquals("the old call is set aside", 1, dao.countDead())
        assertEquals("the young call is still held", 1, dao.counts(current).first().held)
    }

    @Test
    fun `a delivery here revives only this enrolment's exhausted rows`() = runTest {
        val mine = queueCall(current, at = 1L)
        val theirs = queueCall(previous, at = 2L)
        dao.markDeadIds(listOf(mine, theirs), NOW, "budget", DeadReason.BUDGET)
        queueCall(current, at = 3L)
        accept(1)

        val report = drainer().drainAll()

        assertEquals(1, report.revived)
        assertEquals(1, dao.counts(current).first().calls)
        assertEquals("the other enrolment's stays dead", 1, dao.countDead())
    }

    @Test
    fun `a fix is stamped with the enrolment it was taken under`() = runTest {
        LocationQueue(dao) { NOW }.record(
            listOf(LocationFix(19.4, -99.1, NOW)),
            windowSeconds = 180,
            enrollment = previous,
        )

        assertEquals(
            previous,
            dao.take(OutboxKind.LOCATION, 1, enrollment = previous).single().enrollment
        )
        assertEquals(0, dao.take(OutboxKind.LOCATION, 1, enrollment = current).size)
    }

    @Test
    fun `an enrolment is its server and device record, not its token`() {
        val rotated = settings().copy(token = "rotated")

        assertEquals(current, rotated.enrollment)
        assertFalse(current == previous)
        assertNull("nothing to stamp before there is an enrolment", Settings().enrollment)
    }

    // An exhausted row of the old enrolment is never revived here, so calling it
    // undeliverable -- "fix the device in Odoo and they will be retried" -- told
    // the operator to fix a device the row will never be sent to.
    @Test
    fun `an exhausted row of a previous enrolment is held, not undeliverable`() = runTest {
        val theirs = queueCall(previous)
        dao.markDeadIds(listOf(theirs), NOW, "budget", DeadReason.BUDGET)

        val counts = dao.counts(current).first()

        assertEquals(0, counts.undeliverable)
        assertEquals(1, counts.held)
    }

    // Moved twice: the band names the newest previous enrolment, so that is all
    // that its buttons may hand over. Sending the oldest one's rows to this
    // server on the word of a dialog naming another was the leak this closes.
    @Test
    fun `the held band counts, sends and discards one previous enrolment at a time`() = runTest {
        val oldest = settings("phone-00").enrollment!!
        queueCall(oldest, createdAt = NOW - 2, at = 1L)
        queueCall(previous, createdAt = NOW - 1, at = 2L)
        queueCall(previous, createdAt = NOW - 1, at = 3L)

        val counts = dao.counts(current).first()
        assertEquals(previous, counts.heldFor)
        assertEquals("the count is of the enrolment it names", 2, counts.held)

        dao.adoptHeld(current, previous)
        val after = dao.counts(current).first()
        assertEquals(2, after.calls)
        assertEquals(oldest, after.heldFor)
        assertEquals(1, after.held)

        dao.deleteHeldPositions(current, oldest)
        dao.withdrawHeld(current, oldest, NOW)
        assertEquals(0, dao.counts(current).first().held)
        assertEquals(2, dao.counts(current).first().calls)
    }

    // An upgrade's backlog was queued before rows carried their enrolment, and
    // a null row reads as the current enrolment's: moved while that backlog
    // waited out an unreachable server, the handset sent it to the new one.
    @Test
    fun `rows queued before rows were stamped stay with the enrolment that was left`() = runTest {
        val file = File.createTempFile("enrolment", ".preferences_pb").apply { delete() }
        val config = DeviceConfig(
            PreferenceDataStoreFactory.create { file },
            leaving = { dao.claimUnstamped(it) },
        )
        val url = server.url("/").toString().trimEnd('/')
        config.enrol(url, PREVIOUS_DEVICE, "t")
        queueCall(null)

        config.enrol(url, PREVIOUS_DEVICE, "rotated")
        assertEquals("a new token is the same enrolment", 1, dao.counts(previous).first().calls)

        config.enrol(url, CURRENT_DEVICE, "t")
        val counts = dao.counts(current).first()
        assertEquals("not this enrolment's to send", 0, counts.calls)
        assertEquals(1, counts.held)
        assertEquals(previous, counts.heldFor)

        // A policy moves the handset the same way the form does.
        queueCall(null, at = 2L)
        config.applyManaged(
            ManagedValues(
                identifier = "phone-03",
                policyPresent = true,
                presentKeys = setOf("identifier"),
            ),
        )
        assertEquals(0, dao.counts(settings("phone-03").enrollment).first().calls)
        assertEquals(
            "the second move's backlog is the enrolment it left",
            1,
            dao.countOf(OutboxKind.CALL_LOG, enrollment = current),
        )
        assertEquals("nothing is left unstamped", 0, dao.countOf(OutboxKind.CALL_LOG))
        file.delete()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val CURRENT_DEVICE = "phone-02"
        const val PREVIOUS_DEVICE = "phone-01"
    }
}
