package com.odoocompanion.whatsapp

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Robolectric for android.util.Log, which the store writes to when a file will
// not read; nothing else here needs a runtime.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SightingStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var onDisk = emptyList<DocumentFile>()

    private val storeFile by lazy { File(folder.root, "sightings.json") }

    private var clock = 1_000L

    private fun store() = SightingStore(storeFile, { clock }) { onDisk }

    private fun notice(label: String, at: Long = 1_000L) = DocumentNotice(
        app = WhatsAppNotices.WHATSAPP,
        chat = "Agroquímicos del Bajío",
        sender = "Agroquímicos del Bajío",
        phone = "5215512345678",
        label = label,
        at = at,
        isGroup = false,
    )

    @Test
    fun `a document is recorded once however many notices carry it`() = runTest {
        val store = store()

        assertEquals(1, store.record(listOf(notice("CSF.pdf"))))
        assertEquals(0, store.record(listOf(notice("CSF.pdf"))))
        assertEquals(1, store.sightings.value?.size)
    }

    @Test
    fun `one message announced twice a moment apart is one document`() = runTest {
        val store = store()

        store.record(listOf(notice("CSF.pdf", at = 1_000L)))
        store.record(listOf(notice("CSF.pdf", at = 4_000L)))

        assertEquals(1, store.sightings.value?.size)
    }

    @Test
    fun `the same file sent again later is a second document`() = runTest {
        val store = store()

        store.record(listOf(notice("CSF.pdf", at = 1_000L)))
        store.record(
            listOf(notice("CSF.pdf", at = 1_000L + Sighting.SAME_MESSAGE_MILLIS + 1)),
        )

        assertEquals(2, store.sightings.value?.size)
    }

    @Test
    fun `a document whose file is already there is found at once`() = runTest {
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 2048))
        val store = store()

        store.record(listOf(notice("CSF.pdf")))

        val sighting = store.sightings.value!!.single()
        assertTrue(sighting.found)
        assertEquals(2048L, sighting.fileSize)
    }

    @Test
    fun `a document downloaded later is found on the next look`() = runTest {
        val store = store()
        store.record(listOf(notice("CSF.pdf")))
        assertFalse(store.sightings.value!!.single().found)

        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 90_000L, 16))
        store.rematch()

        assertTrue(store.sightings.value!!.single().found)
    }

    @Test
    fun `two notices of the same name take two files, not one`() = runTest {
        onDisk = listOf(
            DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 16),
            DocumentFile("/w/CSF (1).pdf", "CSF (1).pdf", 600_000L, 16),
        )
        val store = store()

        store.record(listOf(notice("CSF.pdf", at = 1_000L), notice("CSF.pdf", at = 600_000L)))

        val paths = store.sightings.value!!.map { it.filePath }.toSet()
        assertEquals(setOf("/w/CSF.pdf", "/w/CSF (1).pdf"), paths)
    }

    @Test
    fun `newest first, and only the last few are kept`() = runTest {
        val store = store()

        store.record(
            (1..SightingStore.MAX_SIGHTINGS + 5).map {
                notice("D$it.pdf", at = it.toLong())
            },
        )

        val kept = store.sightings.value!!
        assertEquals(SightingStore.MAX_SIGHTINGS, kept.size)
        assertEquals("D${SightingStore.MAX_SIGHTINGS + 5}.pdf", kept.first().label)
    }

    @Test
    fun `what was seen survives the process`() = runTest {
        store().record(listOf(notice("CSF.pdf")))

        val reopened = store()
        reopened.open()

        assertEquals("CSF.pdf", reopened.sightings.value!!.single().label)
    }

    @Test
    fun `a damaged file starts the window over instead of failing`() = runTest {
        storeFile.writeText("{not json")
        val store = store()

        store.open()

        assertEquals(emptyList<Sighting>(), store.sightings.value)
    }

    @Test
    fun `the same PDF sent again to the same chat is given the file WhatsApp kept`() = runTest {
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 16))
        val store = store()
        store.record(listOf(notice("CSF.pdf", at = 1_000L)))

        clock = 3_600_000L
        store.record(listOf(notice("CSF.pdf", at = clock)))
        assertFalse(store.sightings.value!!.first().found)

        clock += SightingStore.RESEND_SETTLE_MILLIS
        store.rematch()

        assertEquals(
            listOf("/w/CSF.pdf", "/w/CSF.pdf"),
            store.sightings.value!!.map {
                it.filePath
            }
        )
    }

    // Downloads by hand: the first constancia was written an hour after its
    // notice, when someone tapped it. A second "CSF.pdf" with no new file is
    // then a document nobody has tapped yet, not the first one again.
    @Test
    fun `where downloads wait for a tap, a same-named document is not given the first file`() =
        runTest {
            onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 3_601_000L, 16))
            val store = store()
            store.record(listOf(notice("CSF.pdf", at = 1_000L)))
            clock = 7_200_000L
            store.record(listOf(notice("CSF.pdf", at = clock)))

            clock += SightingStore.RESEND_SETTLE_MILLIS
            store.rematch()
            val sightings = store.sightings.value!!.sortedBy { it.at }
            assertEquals("/w/CSF.pdf", sightings.first().filePath)
            assertEquals("it waits for its own file", null, sightings.last().filePath)

            onDisk = onDisk + DocumentFile("/w/CSF (1).pdf", "CSF (1).pdf", clock + 5_000L, 20)
            store.rematch()
            assertEquals("/w/CSF (1).pdf", store.sightings.value!!.maxBy { it.at }.filePath)
        }

    // Not measured either way on a handset: should WhatsApp write its one copy
    // again when the same file comes back, the file is stamped with the resend's
    // notice. That too is a download that came by itself.
    @Test
    fun `a resend whose arrival rewrote the kept file is still given it`() = runTest {
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 16))
        val store = store()
        store.record(listOf(notice("CSF.pdf", at = 1_000L)))
        clock = 7_200_000L
        store.record(listOf(notice("CSF.pdf", at = clock)))
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", clock + 1_000L, 16))

        clock += SightingStore.RESEND_SETTLE_MILLIS
        store.rematch()

        assertEquals(
            listOf("/w/CSF.pdf", "/w/CSF.pdf"),
            store.sightings.value!!.sortedBy { it.at }.map { it.filePath },
        )
    }

    @Test
    fun `a file no notice claims is taken as loose once its notice had time to come`() = runTest {
        val store = store()
        store.open()
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 2_000L, 16))

        assertEquals(0, store.adoptLooseFiles())

        clock = 2_000L + SightingStore.LOOSE_SETTLE_MILLIS
        assertEquals(1, store.adoptLooseFiles())

        val loose = store.sightings.value!!.single()
        assertTrue(loose.loose)
        assertEquals("/w/CSF.pdf", loose.filePath)
        assertEquals(0, store.adoptLooseFiles())
    }

    @Test
    fun `files from before the watching began are history, not documents`() = runTest {
        onDisk = listOf(DocumentFile("/w/Old.pdf", "Old.pdf", 500L, 16))
        val store = store()
        store.open()

        clock += SightingStore.LOOSE_SETTLE_MILLIS

        assertEquals(0, store.adoptLooseFiles())
    }

    @Test
    fun `a file its notice already took is not loose`() = runTest {
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 2_000L, 16))
        val store = store()
        store.record(listOf(notice("CSF.pdf", at = 2_000L)))

        clock = 2_000L + SightingStore.LOOSE_SETTLE_MILLIS

        assertEquals(0, store.adoptLooseFiles())
        assertFalse(store.sightings.value!!.single().loose)
    }

    @Test
    fun `a notice arriving after its file was taken as loose takes it over`() = runTest {
        val store = store()
        store.open()
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 2_000L, 16))
        clock = 2_000L + SightingStore.LOOSE_SETTLE_MILLIS
        store.adoptLooseFiles()

        store.record(listOf(notice("CSF.pdf", at = 2_500L)))

        val only = store.sightings.value!!.single()
        assertFalse(only.loose)
        assertEquals("Agroquímicos del Bajío", only.chat)
        assertEquals("/w/CSF.pdf", only.filePath)
    }

    @Test
    fun `rows seen before contacts were granted get their number afterwards`() = runTest {
        val store = store()
        store.record(listOf(notice("CSF.pdf").copy(phone = null, sender = "Yop", chat = "Yop")))

        store.fillNumbers { name -> "5215512345678".takeIf { name == "Yop" } }

        assertEquals("5215512345678", store.sightings.value!!.single().phone)
    }

    @Test
    fun `a file that never reads is given up on, and says so`() = runTest {
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 16))
        val store = store()
        store.record(listOf(notice("CSF.pdf")))
        var reads = 0

        repeat(Sighting.MAX_READ_ATTEMPTS + 3) {
            store.classifyPending {
                reads++
                null
            }
        }

        assertEquals(
            "read until the attempts ran out, then left alone",
            Sighting.MAX_READ_ATTEMPTS,
            reads
        )
        val sighting = store.sightings.value!!.single()
        assertTrue(sighting.unreadable)
        val reloaded = store().also { it.open() }.sightings.value!!.single()
        assertEquals(
            "the count survives a restart",
            Sighting.MAX_READ_ATTEMPTS,
            reloaded.readFailures
        )
    }

    // Deleted from WhatsApp's folder, or out of reach while all-files access was
    // revoked: each pass counted a failed read, and five passes later the card
    // said the file could not be read -- and never read it again once it was back.
    @Test
    fun `a file gone from disk is missing, not unreadable, and is read once it is back`() =
        runTest {
            val file = DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 16)
            onDisk = listOf(file)
            val store = store()
            store.record(listOf(notice("CSF.pdf")))
            onDisk = emptyList()
            var reads = 0

            repeat(Sighting.MAX_READ_ATTEMPTS + 1) {
                store.classifyPending {
                    reads++
                    null
                }
            }

            assertEquals("nothing on disk to read", 0, reads)
            val gone = store.sightings.value!!.single()
            assertTrue(gone.missing)
            assertFalse("a missing file is not a damaged one", gone.unreadable)

            onDisk = listOf(file)
            store.classifyPending {
                reads++
                Classification(DocumentKind.INE)
            }

            assertEquals(1, reads)
            val back = store.sightings.value!!.single()
            assertEquals(DocumentKind.INE, back.classification?.kind)
            assertFalse(back.missing)
        }

    @Test
    fun `a found file is read once, and a read that failed is asked again`() = runTest {
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 16))
        val store = store()
        store.record(listOf(notice("CSF.pdf"), notice("Otro.pdf", at = 900_000L)))
        val reads = mutableListOf<String>()
        var failing = true

        store.classifyPending { file ->
            reads += file.name
            if (failing) null else Classification(DocumentKind.CSF, "PCA221013LF1")
        }
        assertEquals(null, store.sightings.value!!.single { it.found }.classification)

        failing = false
        store.classifyPending { file ->
            reads += file.name
            Classification(DocumentKind.CSF, "PCA221013LF1")
        }
        store.classifyPending { file ->
            reads += file.name
            Classification(DocumentKind.OTHER)
        }

        val read = store.sightings.value!!.single { it.found }.classification
        assertEquals(DocumentKind.CSF, read?.kind)
        assertEquals("PCA221013LF1", read?.rfc)
        // Only the file on disk was read, and never again once classified.
        assertEquals(listOf("CSF.pdf", "CSF.pdf"), reads)
    }

    @Test
    fun `a classification survives the process`() = runTest {
        onDisk = listOf(DocumentFile("/w/INE.pdf", "INE.pdf", 1_000L, 16))
        store().apply {
            record(listOf(notice("INE.pdf")))
            classifyPending { Classification(DocumentKind.INE) }
        }

        val reopened = store()
        reopened.open()

        assertEquals(DocumentKind.INE, reopened.sightings.value!!.single().classification?.kind)
    }

    // The window keeps its rows by the notice's time, while WhatsApp may write a
    // file days after the notice: a row trimmed out left its file unclaimed, and
    // the file came back as a loose document -- read by OCR, queued and
    // uploaded a second time, and shown again without its chat or number.
    @Test
    fun `a file whose row left the window is not taken again as loose`() = runTest {
        val store = store()
        onDisk = listOf(DocumentFile("/w/Late.pdf", "Late.pdf", 900_000L, 16))
        store.record(listOf(notice("Late.pdf", at = 1_000L)))
        assertTrue(store.sightings.value!!.single().found)

        store.record(
            (1..SightingStore.MAX_SIGHTINGS).map {
                notice("Doc$it.pdf", at = 2_000L + it)
            }
        )
        clock = 10_000_000L
        store.adoptLooseFiles()

        assertTrue(store.sightings.value!!.none { it.filePath == "/w/Late.pdf" })
        assertEquals(
            "and a store opened afresh remembers it",
            0,
            store().let {
                it.adoptLooseFiles()
                it.sightings.value!!.count { row -> row.filePath == "/w/Late.pdf" }
            },
        )
    }

    // OCR takes seconds a page, and every trigger of the intake runs it.
    @Test
    fun `two passes at once read a file once`() = runTest {
        onDisk = listOf(DocumentFile("/w/CSF.pdf", "CSF.pdf", 1_000L, 16))
        val store = store()
        store.record(listOf(notice("CSF.pdf", at = 1_000L)))
        val reads = AtomicInteger()
        val bothReading = CountDownLatch(2)
        val read = { _: File ->
            reads.incrementAndGet()
            bothReading.countDown()
            bothReading.await(OVERLAP_MILLIS, TimeUnit.MILLISECONDS)
            Classification(DocumentKind.CSF)
        }

        coroutineScope { repeat(2) { launch(Dispatchers.IO) { store.classifyPending(read) } } }

        assertEquals(1, reads.get())
    }

    private companion object {
        const val OVERLAP_MILLIS = 500L
    }
}
