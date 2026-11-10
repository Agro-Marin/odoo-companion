package com.odoocompanion.whatsapp

import android.util.AtomicFile
import android.util.Log
import com.odoocompanion.system.debug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

// A document WhatsApp brought to this handset: announced by a notice, or found
// in WhatsApp's folder with no notice at all -- a loose one, which is what a
// document sent into a chat the person has open looks like, since WhatsApp
// posts nothing for a chat on screen.
@Serializable
data class Sighting(
    val app: String,
    // Empty on a loose sighting: no notice said which chat it came from.
    val chat: String,
    val sender: String,
    val phone: String? = null,
    val label: String,
    val at: Long,
    val isGroup: Boolean = false,
    val filePath: String? = null,
    val fileSize: Long = 0,
    val loose: Boolean = false,
    // What the file says it is, once read; null until then.
    val classification: Classification? = null,
    // Handed to the outbox; whether it was delivered is the outbox's to say.
    val queued: Boolean = false,
    // Reads of its file that failed. A damaged PDF, or one OCR raises on, was
    // read again on every pass for ever and showed as "being read".
    val readFailures: Int = 0,
    // Its file was not on disk at the last pass: deleted from WhatsApp's folder,
    // or out of reach while all-files access was revoked. Not a failed read --
    // counted as one, it ruled the file unreadable and never read it once back.
    val missing: Boolean = false,
) {
    val found: Boolean
        get() = filePath != null

    // Read MAX_READ_ATTEMPTS times without a verdict: not read again.
    val unreadable: Boolean
        get() = found && classification == null && readFailures >= MAX_READ_ATTEMPTS

    // One message, announced twice: measured on a handset, WhatsApp's notices
    // stamped the same document a moment apart, and it showed as two.
    fun sameDocumentAs(notice: DocumentNotice): Boolean =
        app == notice.app && chat == notice.chat && label == notice.label &&
            kotlin.math.abs(at - notice.at) <= SAME_MESSAGE_MILLIS

    companion object {
        const val SAME_MESSAGE_MILLIS = 2L * 60 * 1000

        const val MAX_READ_ATTEMPTS = 5
    }
}

@Serializable
private data class StoredSightings(
    // Files written before this are the handset's history, not documents to
    // watch for: turning the proposal on must not sweep up every PDF WhatsApp
    // ever saved.
    val watchingSince: Long,
    val sightings: List<Sighting> = emptyList(),
    // The files of rows trimmed out of the window, newest last.
    val retired: List<String> = emptyList(),
)

// What this proposal has seen, newest first: kept on the handset alone, in the
// app's private storage, so a real phone can show what WhatsApp brought before
// anything is uploaded. Bounded, because it is a window onto the last few
// documents and not a record.
class SightingStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val scan: () -> List<DocumentFile>,
) {
    private val lock = Mutex()

    private val reading = Mutex()

    private val state = MutableStateFlow<List<Sighting>?>(null)

    private var watchingSince = 0L

    // A row is trimmed by its notice's time, and WhatsApp may write its file
    // days later: the file of a trimmed row, no longer claimed by any, came back
    // as a loose document -- read, queued and uploaded again. So a trimmed row
    // leaves its file here, and the loose pass never takes it.
    private var retired = emptyList<String>()

    val sightings: StateFlow<List<Sighting>?> = state.asStateFlow()

    suspend fun record(notices: List<DocumentNotice>): Int = lock.withLock {
        val known = load()
        val fresh = mutableListOf<Sighting>()
        for (notice in notices) {
            if ((known + fresh).none { it.sameDocumentAs(notice) }) fresh += sightingOf(notice)
        }
        save(matched(fresh + known, scan))
        fresh.size
    }

    // A document downloaded after its notice, by hand or on Wi-Fi later, is
    // found the next time anyone looks.
    suspend fun rematch() = lock.withLock {
        val known = load()
        save(matched(known, scan))
    }

    // Files no notice accounts for. A file is given LOOSE_SETTLE_MILLIS for its
    // notice to arrive and claim it, because with the chat closed the notice and
    // the automatic download race each other. It is short because a notice that
    // loses that race still takes the file over (adoptedFile): the wait saves a
    // loose row, it does not decide where the document ends up.
    suspend fun adoptLooseFiles(): Int = lock.withLock {
        val files = scan()
        val known = matched(load()) { files }
        val claimed = known.mapNotNullTo(HashSet()) { it.filePath }
        val loose = files
            .filter { it.path !in claimed && it.path !in retired }
            .filter { it.modifiedAt >= watchingSince }
            .filter { now() - it.modifiedAt >= LOOSE_SETTLE_MILLIS }
            .map(::looseSightingOf)
        debug(TAG) { "${files.size} file(s) on disk, ${loose.size} loose" }
        save(known + loose)
        loose.size
    }

    // Numbers for what was seen before contacts could be read: granting the
    // permission fills in the rows already on screen, not only the next ones.
    suspend fun fillNumbers(numberOf: (String) -> String?) = lock.withLock {
        val known = load()
        val asked = mutableMapOf<String, String?>()
        val filled = known.map { sighting ->
            if (sighting.phone != null || sighting.loose || sighting.sender.isBlank()) {
                return@map sighting
            }
            val phone = asked.getOrPut(sighting.sender) { numberOf(sighting.sender) }
            if (phone == null) sighting else sighting.copy(phone = phone)
        }
        if (filled != known) save(filled)
    }

    // Reads each found file not yet classified. The reading is done outside the
    // lock -- OCR takes a second or two a page -- and its verdict applied under
    // it, to whichever row still holds that file; a read that failed is left
    // for the next pass, and counted, until MAX_READ_ATTEMPTS gives up on it.
    // A file no longer on disk is not read, and not counted: it is missing
    // until it is back.
    // A file another row already read -- a resend WhatsApp kept one copy of --
    // takes that verdict instead of a second OCR.
    // One pass at a time, under its own lock so the store stays free while a
    // page is read: two passes both found the same file unread and read it.
    suspend fun classifyPending(read: (File) -> Classification?): Int = reading.withLock {
        val (earlier, pending) = lock.withLock {
            val all = load()
            val earlier = all.mapNotNull { row ->
                val path = row.filePath ?: return@mapNotNull null
                row.classification?.let { path to it }
            }.toMap()
            earlier to all.filter { it.found && it.classification == null && !it.unreadable }
                .mapNotNull { it.filePath }
                .filterNot { it in earlier }
                .distinct()
        }
        val onDisk = if (pending.isEmpty()) emptySet() else scan().mapTo(HashSet()) { it.path }
        val fresh = pending.filter { it in onDisk }
            .mapNotNull { path -> read(File(path))?.let { path to it } }
            .toMap()
        val verdicts = earlier + fresh
        lock.withLock {
            val known = load()
            val classified = known.map { sighting ->
                val path = sighting.filePath
                val verdict = verdicts[path]
                when {
                    sighting.classification != null -> sighting

                    verdict != null -> sighting.copy(classification = verdict, missing = false)

                    path in pending && path !in onDisk -> sighting.copy(missing = true)

                    path in pending -> sighting.copy(
                        readFailures = sighting.readFailures + 1,
                        missing = false,
                    )

                    else -> sighting
                }
            }
            if (classified != known) save(classified)
        }
        fresh.size
    }

    // Found, read as a document that goes to Odoo, and not yet queued.
    suspend fun toUpload(): List<Sighting> = lock.withLock {
        load().filter { it.found && !it.queued && it.classification?.kind?.uploads == true }
    }

    suspend fun markQueued(paths: Set<String>) = lock.withLock {
        val known = load()
        if (known.none { !it.queued && it.filePath in paths }) return@withLock
        save(known.map { if (it.filePath in paths) it.copy(queued = true) else it })
    }

    // Loads the store into the flow, for a screen that opens before any notice.
    suspend fun open() = lock.withLock { load() }

    // The folders are scanned only when a sighting still waits for its file:
    // WhatsApp posts a chat again with every new message, and each repost
    // scanned them all.
    private fun matched(
        sightings: List<Sighting>,
        scan: () -> List<DocumentFile>,
    ): List<Sighting> {
        if (sightings.none { !it.found }) return sightings
        val files = scan()
        val claimed = sightings.mapNotNullTo(HashSet()) { it.filePath }
        val loosePaths = sightings.filter { it.loose }.mapNotNullTo(HashSet()) { it.filePath }
        val adopted = mutableSetOf<String>()
        val result = mutableListOf<Sighting>()
        // Oldest first, so a resend finds the sighting that took the file.
        for (sighting in sightings.sortedBy { it.at }) {
            if (sighting.loose && sighting.filePath in adopted) continue
            if (sighting.found) {
                result += sighting
                continue
            }
            val notice = noticeOf(sighting)
            val file = DocumentMatcher.match(notice, files, claimed)?.also { claimed += it.path }
                ?: adoptedFile(notice, files, loosePaths - adopted)
                    ?.also { file ->
                        adopted += file.path
                        result.removeAll { it.loose && it.filePath == file.path }
                    }
                ?: resentFile(sighting, result, files)
            result += file?.let {
                sighting.copy(filePath = it.path, fileSize = it.size)
            } ?: sighting
        }
        debug(TAG) { "${result.count { it.found }} of ${result.size} sighting(s) matched" }
        return result
    }

    // A notice that arrived after its file had already been taken as loose: the
    // notice says who sent it, so it takes the file and the loose row goes.
    private fun adoptedFile(
        notice: DocumentNotice,
        files: List<DocumentFile>,
        loosePaths: Set<String>,
    ): DocumentFile? {
        if (loosePaths.isEmpty()) return null
        return DocumentMatcher.match(notice, files.filter { it.path in loosePaths }, emptySet())
    }

    // WhatsApp keeps one copy of a file it already has: measured on a handset,
    // the same PDF sent to the same chat again produced a notice and no new
    // file, and the one file was already taken by the first notice. A resend
    // in the same chat, under the same name, is given that file -- once
    // RESEND_SETTLE_MILLIS has passed with no fresh download, so a different
    // document of the same name still being downloaded is not mistaken for it.
    // And only where downloads are seen to be automatic: the first file was
    // written with its notice. Where they are not, no new file is what any
    // document nobody has tapped yet looks like, and a different constancia
    // under the same name was given the first one's file -- linked to the
    // wrong message, the real one never read. It waits for its tap instead.
    private fun resentFile(
        sighting: Sighting,
        earlier: List<Sighting>,
        files: List<DocumentFile>,
    ): DocumentFile? {
        if (now() - sighting.at < RESEND_SETTLE_MILLIS) return null
        val first = earlier.firstOrNull {
            it.found && it.app == sighting.app && it.chat == sighting.chat &&
                it.label == sighting.label
        } ?: return null
        val file = files.firstOrNull { it.path == first.filePath } ?: return null
        // Written with either notice: the first's, or this one's, should WhatsApp
        // write the one copy again when the same file comes back. Neither is
        // what a document nobody has tapped looks like.
        return file.takeIf { cameWithItsNotice(it, first) || cameWithItsNotice(it, sighting) }
    }

    private fun cameWithItsNotice(file: DocumentFile, sighting: Sighting): Boolean =
        file.modifiedAt - sighting.at in
            -DocumentMatcher.BEFORE_NOTICE_MILLIS..RESEND_SETTLE_MILLIS

    private fun load(): List<Sighting> {
        state.value?.let { return it }
        val read = try {
            STORE_JSON.decodeFromString<StoredSightings>(
                AtomicFile(file).readFully().decodeToString(),
            )
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the sightings; starting over", e)
            null
        } catch (e: SerializationException) {
            // Also the first build's format, a bare list: its rows predate the
            // fixes above, so nothing worth keeping is lost.
            Log.w(TAG, "Could not decode the sightings; starting over", e)
            null
        }
        watchingSince = read?.watchingSince ?: now()
        retired = read?.retired.orEmpty()
        val sightings = read?.sightings.orEmpty()
        if (read == null) save(sightings) else state.value = sightings
        return sightings
    }

    // Written only when it changed: a repost that brought nothing new rewrote
    // the file every time.
    private fun save(sightings: List<Sighting>) {
        val ordered = sightings.sortedByDescending { it.at }
        val kept = ordered.take(MAX_SIGHTINGS)
        val trimmed = ordered.drop(MAX_SIGHTINGS).mapNotNull { it.filePath }
        if (kept == state.value && trimmed.all { it in retired }) return
        retired = (retired - trimmed.toSet() + trimmed).takeLast(MAX_RETIRED)
        state.value = kept
        // Written aside and renamed over: written in place, a process killed
        // mid-write left a file that would not decode, and the store started over.
        val atomic = AtomicFile(file)
        val out = try {
            atomic.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the sightings", e)
            return
        }
        try {
            val stored = StoredSightings(watchingSince, kept, retired)
            out.write(STORE_JSON.encodeToString(stored).toByteArray())
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            Log.w(TAG, "Could not write the sightings", e)
        }
    }

    private fun sightingOf(notice: DocumentNotice) = Sighting(
        app = notice.app,
        chat = notice.chat,
        sender = notice.sender,
        phone = notice.phone,
        label = notice.label,
        at = notice.at,
        isGroup = notice.isGroup,
    )

    private fun looseSightingOf(file: DocumentFile) = Sighting(
        app = WhatsAppDocuments.appOf(file.path),
        chat = "",
        sender = "",
        label = file.name,
        at = file.modifiedAt,
        filePath = file.path,
        fileSize = file.size,
        loose = true,
    )

    private fun noticeOf(sighting: Sighting) = DocumentNotice(
        app = sighting.app,
        chat = sighting.chat,
        sender = sighting.sender,
        phone = sighting.phone,
        label = sighting.label,
        at = sighting.at,
        isGroup = sighting.isGroup,
    )

    companion object {
        private const val TAG = "WhatsAppSightings"

        const val MAX_SIGHTINGS = 50

        // Ten windows' worth: a path is a hundred-odd bytes, and a file older
        // than this many documents is far past any notice that could claim it.
        const val MAX_RETIRED = 500

        const val RESEND_SETTLE_MILLIS = 60_000L

        const val LOOSE_SETTLE_MILLIS = 10_000L

        private val STORE_JSON = Json { ignoreUnknownKeys = true }
    }
}
