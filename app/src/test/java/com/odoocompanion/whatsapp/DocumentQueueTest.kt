package com.odoocompanion.whatsapp

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.WireJson
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DocumentQueueTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: CompanionDatabase
    private lateinit var dao: OutboxDao

    private var onDisk = emptyList<DocumentFile>()

    private var clock = 10_000L

    private val store by lazy { SightingStore(File(folder.root, "s.json"), { clock }) { onDisk } }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.outbox()
    }

    @After
    fun tearDown() = database.close()

    private fun notice(label: String, at: Long, chat: String = "Yop") = DocumentNotice(
        app = WhatsAppNotices.WHATSAPP,
        chat = chat,
        sender = chat,
        phone = "5215512345678",
        label = label,
        at = at,
        isGroup = false,
    )

    private suspend fun seen(vararg kinds: Pair<String, DocumentKind>) {
        onDisk = kinds.mapIndexed { i, (name, _) ->
            DocumentFile("/w/$name", name, 1_000L + i * 600_000L, 16)
        }
        store.record(kinds.mapIndexed { i, (name, _) -> notice(name, 1_000L + i * 600_000L) })
        val byName = kinds.toMap()
        store.classifyPending { file -> Classification(byName.getValue(file.name)) }
    }

    private fun queue() = DocumentQueue(store, dao) { 5L }

    @Test
    fun `only a CSF, an INE or a passport is queued, once, with what the phone knows`() = runTest {
        seen(
            "CSF.pdf" to DocumentKind.CSF,
            "INE.pdf" to DocumentKind.INE,
            "Cotizacion.pdf" to DocumentKind.OTHER,
        )

        assertEquals(2, queue().queueClassified())
        assertEquals(0, queue().queueClassified())

        assertEquals(
            setOf("/w/CSF.pdf", "/w/INE.pdf"),
            dao.filesQueued(OutboxKind.DOCUMENT).toSet()
        )
        val csf = dao.take(OutboxKind.DOCUMENT, 10, Long.MAX_VALUE)
            .single { it.filePath == "/w/CSF.pdf" }
        val sent = WireJson.decodeFromString(DocumentMetadata.serializer(), csf.payload)
        assertEquals("csf", sent.kind)
        assertEquals("Yop", sent.chat)
        assertEquals("5215512345678", sent.senderPhone)
        assertEquals("WhatsApp", sent.source)
        assertEquals("application/pdf", sent.mimetype)
    }

    @Test
    fun `a resend that shares the first one's file is not queued a second time`() = runTest {
        seen("CSF.pdf" to DocumentKind.CSF)
        queue().queueClassified()
        // WhatsApp kept one file for both: the store gave the resend that file.
        store.record(listOf(notice("CSF.pdf", at = 9_999_000L)))
        clock = 9_999_000L + SightingStore.RESEND_SETTLE_MILLIS
        store.rematch()
        assertEquals(0, store.classifyPending { error("the file was already read") })

        assertEquals(0, queue().queueClassified())
        assertTrue(store.sightings.value!!.all { it.queued })
    }

    @Test
    fun `what the queue holds says where each document stands`() {
        val csf = Sighting(
            key = "k",
            app = WhatsAppNotices.WHATSAPP,
            chat = "Yop",
            sender = "Yop",
            label = "CSF.pdf",
            at = 1L,
            filePath = "/w/CSF.pdf",
            classification = Classification(DocumentKind.CSF),
        )

        assertEquals(UploadState.WAITING, WhatsAppIntake.uploadStateOf(csf, emptyMap()))
        val queued = csf.copy(queued = true)
        assertEquals(
            UploadState.QUEUED,
            WhatsAppIntake.uploadStateOf(queued, mapOf("/w/CSF.pdf" to false)),
        )
        assertEquals(
            UploadState.SET_ASIDE,
            WhatsAppIntake.uploadStateOf(queued, mapOf("/w/CSF.pdf" to true)),
        )
        assertEquals(UploadState.DELIVERED, WhatsAppIntake.uploadStateOf(queued, emptyMap()))
        val other = csf.copy(classification = Classification(DocumentKind.OTHER))
        assertNull(WhatsAppIntake.uploadStateOf(other, emptyMap()))
    }
}
