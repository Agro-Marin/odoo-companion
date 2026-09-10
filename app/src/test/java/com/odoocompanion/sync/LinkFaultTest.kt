package com.odoocompanion.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.OdooClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkFaultTest {
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

    private fun drainer() = OutboxDrainer(dao, OdooClient(), { 5_000L }) {
        Settings(
            baseUrl = server.url("/").toString().trimEnd('/'),
            identifier = "phone-01",
            token = "t",
        )
    }

    private fun deadReason(): String? = database.query("SELECT deadReason FROM outbox", null)
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun answer(code: Int, body: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = code, body = body)
        }
    }

    @Test
    fun `a rotated token alone, with no positions flowing, never retires a call`() = runTest {
        dao.insert(
            OutboxEntry(
                kind = OutboxKind.CALL_LOG,
                payload = """{"number":"+5255000","direction":"incoming",""" +
                    """"timestamp":1,"duration":1}""",
                createdAt = 1L,
            ),
        )
        answer(401, """{"error":"authentication_failed","message":"Invalid credentials"}""")
        val drainer = drainer()
        val reasons = mutableListOf<String?>()
        repeat(OutboxDrainer.MAX_ATTEMPTS + 3 * (OutboxDrainer.MAX_REVIVALS + 1) + 5) {
            drainer.drainAll()
            reasons += deadReason()
        }
        assertTrue(
            "a 401 is the link's fault, so the row cycles between budget-dead and one probe",
            reasons.last() == DeadReason.BUDGET || reasons.last() == null,
        )
        assertEquals(emptyList<String?>(), reasons.filter { it == DeadReason.REFUSED })
    }
}
