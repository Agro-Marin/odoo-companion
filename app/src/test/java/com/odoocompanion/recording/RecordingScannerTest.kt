package com.odoocompanion.recording

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingScannerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1_000_000_000L

    private fun scanner() = RecordingScanner(folder.root) { clock }

    private fun recording(path: String, ageMillis: Long, bytes: Int = 32): File {
        val file = File(folder.root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        file.setLastModified(clock - ageMillis)
        return file
    }

    private fun names(since: Long = 0) = scanner().scan(since).map { it.file.name }

    @Test
    fun `a settled recording in a known directory is found`() {
        recording("Recordings/Call/call_5512345678.m4a", ageMillis = 10 * 60_000)

        assertEquals(listOf("call_5512345678.m4a"), names())
    }

    @Test
    fun `a recording still being written is left for a later pass`() {
        recording("Recordings/Call/in_progress.m4a", ageMillis = 5_000)

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `and is picked up once it has settled`() {
        recording("Recordings/Call/in_progress.m4a", ageMillis = 5_000)
        assertEquals(emptyList<String>(), names())

        clock += RecordingScanner.SETTLE_MILLIS

        assertEquals(listOf("in_progress.m4a"), names())
    }

    @Test
    fun `holding a file back never moves the cursor past it`() {
        val settled = recording("Recordings/Call/done.m4a", ageMillis = 10 * 60_000)
        val writing = recording("Recordings/Call/writing.m4a", ageMillis = 1_000)

        val newestScanned = scanner().scan(0).maxOf { it.modifiedAt }

        assertEquals(settled.lastModified(), newestScanned)
        assertEquals(true, newestScanned < writing.lastModified())
    }

    @Test
    fun `the scan reports the modification time it filtered on`() {
        val settled = recording("Recordings/Call/done.m4a", ageMillis = 10 * 60_000)

        val scanned = scanner().scan(0).single()

        assertEquals(settled.absolutePath, scanned.file.absolutePath)
        assertEquals(settled.lastModified(), scanned.modifiedAt)
    }

    @Test
    fun `a file reachable through two candidate directories is returned once`() {
        recording("Recordings/Call/twice.m4a", ageMillis = 10 * 60_000)

        assertEquals(1, scanner().scan(0).size)
    }

    @Test
    fun `files at or before the cursor are not returned again`() {
        val old = recording("Recordings/Call/old.m4a", ageMillis = 60 * 60_000)
        recording("Recordings/Call/new.m4a", ageMillis = 10 * 60_000)

        assertEquals(listOf("new.m4a"), names(since = old.lastModified()))
    }

    @Test
    fun `non-audio files and unknown directories are ignored`() {
        recording("Recordings/Call/notes.txt", ageMillis = 10 * 60_000)
        recording("Download/song.m4a", ageMillis = 10 * 60_000)

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `results are ordered oldest first so the cursor advances monotonically`() {
        recording("Recordings/Call/c.m4a", ageMillis = 10 * 60_000)
        recording("Recordings/Call/a.m4a", ageMillis = 30 * 60_000)
        recording("Recordings/Call/b.m4a", ageMillis = 20 * 60_000)

        assertEquals(listOf("a.m4a", "b.m4a", "c.m4a"), names())
    }

    @Test
    fun `a missing external directory is not an error`() {
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun `a voice memo sitting beside the call recordings is not harvested`() {
        recording("Recordings/Call/call_5512345678.m4a", ageMillis = 10 * 60_000)
        recording("Recordings/Voice Recorder/memo-001.m4a", ageMillis = 10 * 60_000)
        recording("Recordings/Voice/note.m4a", ageMillis = 10 * 60_000)

        assertEquals(listOf("call_5512345678.m4a"), names())
    }
}
