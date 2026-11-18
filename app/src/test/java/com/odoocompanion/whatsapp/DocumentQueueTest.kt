package com.odoocompanion.whatsapp

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.odoocompanion.config.Settings
import com.odoocompanion.data.CompanionDatabase
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.data.UploadState
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.OdooClient
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.OutboxDrainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        ShadowLog.setLoggable("DocumentQueue", Log.DEBUG)
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

    private fun documentFile(name: String, modified: Long, size: Int): DocumentFile {
        val file = File(folder.root, name)
        file.writeBytes(ByteArray(size))
        assertTrue(file.setLastModified(modified))
        return DocumentFile(file.path, name, modified, size.toLong())
    }

    private suspend fun seen(vararg kinds: Pair<String, DocumentKind>) {
        onDisk = kinds.mapIndexed { i, (name, _) ->
            documentFile(name, 1_000L + i * 600_000L, 16)
        }
        store.record(kinds.mapIndexed { i, (name, _) -> notice(name, 1_000L + i * 600_000L) })
        val byName = kinds.toMap()
        store.classifyPending { file ->
            DocumentReader(folder.root) {
                Classification(byName.getValue(file.name))
            }.classify(file)
        }
    }

    private fun queue() = DocumentQueue(store, dao) { 5L }

    private fun queue(deadReason: String?) =
        mapOf("/w/CSF.pdf" to listOf(QueuedFileState("/w/CSF.pdf", deadReason)))

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
            setOf(File(folder.root, "CSF.pdf").path, File(folder.root, "INE.pdf").path),
            dao.filesQueued(OutboxKind.DOCUMENT).toSet()
        )
        val csf = dao.take(OutboxKind.DOCUMENT, 10, Long.MAX_VALUE)
            .single { it.filePath == File(folder.root, "CSF.pdf").path }
        val sent = WireJson.decodeFromString(DocumentMetadata.serializer(), csf.payload)
        assertEquals("csf", sent.kind)
        assertEquals("Yop", sent.chat)
        assertEquals("5215512345678", sent.senderPhone)
        assertEquals("WhatsApp", sent.source)
        assertEquals("application/pdf", sent.mimetype)
    }

    @Test
    fun `delivery after an interrupted queue marker survives restart without requeueing`() =
        runTest {
            seen("CSF.pdf" to DocumentKind.CSF)
            val file = File(folder.root, "s.json")
            val beforeMarker = file.readText()
            assertEquals(1, queue().queueClassified())
            file.writeText(beforeMarker)
            val reopened = SightingStore(file) { onDisk }
            reopened.open()
            val row = dao.take(OutboxKind.DOCUMENT, 1).single()
            val path = requireNotNull(row.filePath)
            reopened.remember(listOf(QueuedFileState(path, DeadReason.ACKNOWLEDGED, row.source)))
            dao.delete(listOf(row.id))

            val next = SightingStore(file) { onDisk }
            assertEquals(0, DocumentQueue(next, dao).queueClassified())
            assertEquals(
                UploadState.DELIVERED,
                WhatsAppIntake.uploadStateOf(next.sightings.value!!.single(), emptyMap())
            )
            assertTrue(dao.filesQueued(OutboxKind.DOCUMENT).isEmpty())
        }

    @Test
    fun `a resend that shares the first one's file is not queued a second time`() = runTest {
        seen("CSF.pdf" to DocumentKind.CSF)
        queue().queueClassified()
        store.record(listOf(notice("CSF.pdf", at = 9_999_000L)))
        clock = 9_999_000L + SightingStore.RESEND_SETTLE_MILLIS
        store.rematch()
        assertEquals(0, store.classifyPending { error("the file was already read") })

        assertEquals(0, queue().queueClassified())
        assertTrue(store.sightings.value!!.all { it.queued })
    }

    private fun writeLegacyHistory(outcome: String = "") {
        File(folder.root, "s.json").writeText(
            """
            {"watchingSince":1,"sightings":[{
                "app":"com.whatsapp","chat":"Yop","sender":"Yop","label":"id.pdf",
                "at":1,"filePath":"/w/id.pdf","classification":{"kind":"INE"}$outcome
            }]}
            """.trimIndent()
        )
    }

    @Test
    fun `older saved receipts suppress uploads even when the queue marker is absent`() = runTest {
        writeLegacyHistory(""","outcome":"DELIVERED" """)
        assertEquals(0, queue().queueClassified())
        val sighting = store.sightings.value!!.single()
        assertEquals(UploadState.DELIVERED, WhatsAppIntake.uploadStateOf(sighting, emptyMap()))
    }

    @Test
    fun `an older pending verdict must be checked against the file before it can leave`() =
        runTest {
            writeLegacyHistory()
            onDisk = listOf(DocumentFile("/w/id.pdf", "id.pdf", 1000, 42))
            assertEquals(0, queue().queueClassified())
            assertEquals(1, store.classifyPending { Classification(DocumentKind.OTHER) })
            assertEquals(0, queue().queueClassified())
            assertTrue(dao.filesQueued(OutboxKind.DOCUMENT).isEmpty())
        }

    @Test
    fun `a resend matched to the delivered file inherits its receipt after cleanup`() = runTest {
        seen("CSF.pdf" to DocumentKind.CSF)
        queue().queueClassified()
        val row = dao.take(OutboxKind.DOCUMENT, 1).single()
        store.remember(
            listOf(
                QueuedFileState(requireNotNull(row.filePath), DeadReason.ACKNOWLEDGED, row.source)
            )
        )
        dao.delete(listOf(row.id))
        store.record(listOf(notice("CSF.pdf", at = 9_999_000L)))
        clock = 9_999_000L + SightingStore.RESEND_SETTLE_MILLIS
        store.rematch()
        store.classifyPending { error("the classified file is unchanged") }

        assertEquals(0, queue().queueClassified())
        assertTrue(store.sightings.value!!.all { it.outcome == UploadState.DELIVERED })
    }

    @Test
    fun `two classified versions sharing a path retain independent queue rows and receipts`() =
        runTest {
            seen("CSF.pdf" to DocumentKind.CSF)
            assertEquals(1, queue().queueClassified())
            val later = 9_999_000L
            onDisk = listOf(documentFile("CSF.pdf", later, 32))
            clock = later + SightingStore.RESEND_SETTLE_MILLIS
            store.record(listOf(notice("CSF.pdf", at = later)))
            store.classifyPending(
                DocumentReader(folder.root) {
                    Classification(DocumentKind.INE)
                }::classify
            )

            assertEquals(1, queue().queueClassified())
            val rows = dao.take(OutboxKind.DOCUMENT, 2)
            assertEquals(listOf(16L, 32L), rows.map { it.source?.size })
            val path = requireNotNull(rows.first().filePath)
            val first = QueuedFileState(path, DeadReason.GONE, rows.first().source)
            val second = QueuedFileState(path, DeadReason.ACKNOWLEDGED, rows.last().source)
            store.remember(listOf(second))
            store.remember(listOf(first))
            val sightings = store.sightings.value!!.sortedBy { it.at }
            assertEquals(
                listOf(UploadState.GONE, UploadState.DELIVERED),
                sightings.map {
                    it.outcome
                }
            )
            val queue = mapOf(path to listOf(first, second.copy(deadReason = null)))
            assertEquals(UploadState.GONE, WhatsAppIntake.uploadStateOf(sightings.first(), queue))
            assertEquals(UploadState.QUEUED, WhatsAppIntake.uploadStateOf(sightings.last(), queue))
        }

    @Test
    fun `a replacement at a classified document path is never sent under its old verdict`() =
        runTest {
            val pdf = folder.newFile("id.pdf").apply {
                writeText("document read by the classifier")
            }
            onDisk = listOf(DocumentFile(pdf.path, pdf.name, pdf.lastModified(), pdf.length()))
            store.record(listOf(notice(pdf.name, at = pdf.lastModified())))
            store.classifyPending(
                DocumentReader(folder.root) {
                    Classification(DocumentKind.INE)
                }::classify
            )
            assertEquals(1, queue().queueClassified())
            pdf.writeText("different document")

            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = """{"status":"success","document_id":1}"""))
                val report = OutboxDrainer(dao, OdooClient()) {
                    Settings(
                        baseUrl = server.url("/").toString(),
                        identifier = "phone",
                        token = "t"
                    )
                }.drainAll()
                assertEquals(0, server.requestCount)
                assertEquals(1, report.lost)
                assertEquals("different document", pdf.readText())
            }
        }

    @Test
    fun `the private copy retains the hashed bytes when the shared path changes during OCR`() {
        val pdf = folder.newFile("source.pdf").apply { writeText("original") }
        val cache = folder.newFolder("scratch")
        val original = pdf.readBytes()
        val digest = okio.ByteString.of(*original).sha256().hex()
        DocumentSnapshot.use(pdf, pdf.length(), cache) { snapshot, captured ->
            pdf.writeText("replaced")
            assertEquals(emptyList<String>(), cache.list()!!.toList())
            val bytes = ByteArray(original.size)
            snapshot.readFully(bytes)
            assertEquals(original.toList(), bytes.toList())
            assertEquals(digest, captured)
        }
        assertEquals(emptyList<String>(), cache.list()!!.toList())
    }

    @Test
    fun `a same size replacement with the original timestamp cannot use the classified verdict`() =
        runTest {
            val pdf = folder.newFile("id.pdf").apply {
                writeText("document read by the classifier")
            }
            onDisk = listOf(DocumentFile(pdf.path, pdf.name, pdf.lastModified(), pdf.length()))
            store.record(listOf(notice(pdf.name, at = pdf.lastModified())))
            val reader = DocumentReader(folder.root) { Classification(DocumentKind.INE) }
            store.classifyPending(reader::classify)
            val digest = store.sightings.value!!.single().classification?.source?.sha256
            assertEquals(DocumentSnapshot.digest(pdf, pdf.length()), digest)
            assertEquals(1, queue().queueClassified())
            val modified = pdf.lastModified()
            val replacement = "x".repeat(pdf.length().toInt())
            pdf.writeText(replacement)
            assertTrue(pdf.setLastModified(modified))

            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse(body = """{"status":"success","document_id":1}"""))
                val report = OutboxDrainer(dao, OdooClient()) {
                    Settings(
                        baseUrl = server.url("/").toString(),
                        identifier = "phone",
                        token = "t"
                    )
                }.drainAll()
                assertEquals(0, server.requestCount)
                assertEquals(1, report.lost)
                assertEquals(replacement, pdf.readText())
            }
        }

    @Test
    fun `a resend with matching metadata and different bytes gets its own verdict and receipt`() =
        runTest {
            val pdf = folder.newFile("id.pdf").apply { writeText("1111") }
            val at = pdf.lastModified()
            val file = DocumentFile(pdf.path, pdf.name, at, pdf.length())
            onDisk = listOf(file)
            store.record(listOf(notice(pdf.name, at = at)))
            val reader = DocumentReader(folder.root) { Classification(DocumentKind.INE) }
            store.classifyPending(reader::classify)
            assertEquals(1, queue().queueClassified())
            val first = dao.take(OutboxKind.DOCUMENT, 1).single()
            store.remember(listOf(QueuedFileState(pdf.path, DeadReason.ACKNOWLEDGED, first.source)))
            dao.delete(listOf(first.id))
            pdf.writeText("2222")
            assertTrue(pdf.setLastModified(at))
            val resent = at + Sighting.SAME_MESSAGE_MILLIS + 1
            clock = resent + SightingStore.RESEND_SETTLE_MILLIS
            onDisk = listOf(file.copy())
            store.record(listOf(notice(pdf.name, at = resent)))

            assertEquals(1, store.classifyPending(reader::classify))
            assertEquals(1, queue().queueClassified())
            val second = dao.take(OutboxKind.DOCUMENT, 1).single()
            assertTrue(first.source!!.sha256 != second.source!!.sha256)
            store.remember(listOf(QueuedFileState(pdf.path, DeadReason.GONE, second.source)))
            val sorted = store.sightings.value!!.sortedBy { it.at }
            assertEquals(listOf(UploadState.DELIVERED, UploadState.GONE), sorted.map { it.outcome })
        }

    @Test
    fun `history keeps the last outcome of a shared document across restart`() = runTest {
        seen("CSF.pdf" to DocumentKind.CSF, "INE.pdf" to DocumentKind.INE)
        queue().queueClassified()
        store.remember(
            dao.take(OutboxKind.DOCUMENT, 2).map {
                val path = requireNotNull(it.filePath)
                val reason = if (File(path).name ==
                    "CSF.pdf"
                ) {
                    DeadReason.GONE
                } else {
                    DeadReason.ACKNOWLEDGED
                }
                QueuedFileState(path, reason, it.source)
            }
        )
        val reopened = SightingStore(File(folder.root, "s.json")) { emptyList() }
        reopened.open()
        val outcomes = reopened.sightings.value!!.associate {
            it.label to WhatsAppIntake.uploadStateOf(it, emptyMap())
        }
        assertEquals(
            mapOf("CSF.pdf" to UploadState.GONE, "INE.pdf" to UploadState.DELIVERED),
            outcomes
        )
    }

    @Test
    fun `what the queue holds says where each document stands`() {
        val csf = Sighting(
            app = WhatsAppNotices.WHATSAPP,
            chat = "Yop",
            sender = "Yop",
            label = "CSF.pdf",
            at = 1L,
            filePath = "/w/CSF.pdf",
            classification = Classification(DocumentKind.CSF),
        )

        assertEquals(UploadState.WAITING, WhatsAppIntake.uploadStateOf(csf, emptyMap()))
        assertEquals(UploadState.QUEUED, WhatsAppIntake.uploadStateOf(csf, queue(null)))
        assertEquals(
            UploadState.DELIVERED,
            WhatsAppIntake.uploadStateOf(csf.copy(outcome = UploadState.DELIVERED), emptyMap())
        )
        val queued = csf.copy(queued = true)
        assertEquals(
            UploadState.QUEUED,
            WhatsAppIntake.uploadStateOf(queued, queue(null)),
        )
        assertEquals(
            UploadState.SET_ASIDE,
            WhatsAppIntake.uploadStateOf(queued, queue(DeadReason.REFUSED)),
        )
        assertEquals(UploadState.UNCONFIRMED, WhatsAppIntake.uploadStateOf(queued, emptyMap()))
        val confirmed = queued.copy(outcome = UploadState.DELIVERED)
        assertEquals(UploadState.DELIVERED, WhatsAppIntake.uploadStateOf(confirmed, emptyMap()))
        val other = csf.copy(classification = Classification(DocumentKind.OTHER))
        assertNull(WhatsAppIntake.uploadStateOf(other, emptyMap()))
    }

    @Test
    fun `two intakes at once queue a document once`() = runTest {
        seen("CSF.pdf" to DocumentKind.CSF)
        val bothLooking = CountDownLatch(2)
        val overlapping = object : OutboxDao by dao {
            override fun fileStates(kind: String) = dao.fileStates(kind).onEach {
                bothLooking.countDown()
                bothLooking.await(OVERLAP_MILLIS, TimeUnit.MILLISECONDS)
            }
        }

        coroutineScope {
            repeat(2) {
                launch(Dispatchers.IO) {
                    DocumentQueue(store, overlapping) { 5L }.queueClassified()
                }
            }
        }

        assertEquals(
            listOf(File(folder.root, "CSF.pdf").path),
            dao.filesQueued(OutboxKind.DOCUMENT)
        )
    }

    private companion object {
        const val OVERLAP_MILLIS = 500L
    }
}
