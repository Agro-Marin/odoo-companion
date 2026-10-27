package com.odoocompanion.whatsapp

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
import java.io.IOException

// A document WhatsApp brought to this handset: announced by a notice, or found
// in WhatsApp's folder with no notice at all -- a loose one, which is what a
// document sent into a chat the person has open looks like, since WhatsApp
// posts nothing for a chat on screen.
@Serializable
data class Sighting(
    val key: String,
    val app: String,
    // Empty on a loose sighting: no notice said which chat it came from.
    val chat: String,
    val sender: String,
    val phone: String? = null,
    val label: String,
    val at: Long,
    val isGroup: Boolean = false,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val loose: Boolean = false,
    // What the file says it is, once read; null until then.
    val classification: Classification? = null,
) {
    val found: Boolean
        get() = filePath != null

    // One message, announced twice: measured on a handset, WhatsApp's notices
    // stamped the same document a moment apart, and it showed as two.
    fun sameDocumentAs(notice: DocumentNotice): Boolean = key == notice.key ||
        (
            app == notice.app && chat == notice.chat && label == notice.label &&
                kotlin.math.abs(at - notice.at) <= SAME_MESSAGE_MILLIS
            )

    companion object {
        const val SAME_MESSAGE_MILLIS = 2L * 60 * 1000
    }
}

@Serializable
private data class StoredSightings(
    // Files written before this are the handset's history, not documents to
    // watch for: turning the proposal on must not sweep up every PDF WhatsApp
    // ever saved.
    val watchingSince: Long,
    val sightings: List<Sighting> = emptyList(),
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

    private val state = MutableStateFlow<List<Sighting>?>(null)

    private var watchingSince = 0L

    val sightings: StateFlow<List<Sighting>?> = state.asStateFlow()

    suspend fun record(notices: List<DocumentNotice>): Int = lock.withLock {
        val known = load()
        val fresh = mutableListOf<Sighting>()
        for (notice in notices) {
            if ((known + fresh).none { it.sameDocumentAs(notice) }) fresh += sightingOf(notice)
        }
        save(matched(fresh + known, scan()))
        fresh.size
    }

    // A document downloaded after its notice, by hand or on Wi-Fi later, is
    // found the next time anyone looks.
    suspend fun rematch() = lock.withLock {
        val known = load()
        if (known.any { !it.found }) save(matched(known, scan()))
    }

    // Files no notice accounts for. A file is given LOOSE_SETTLE_MILLIS for its
    // notice to arrive and claim it, because with the chat closed the notice and
    // the automatic download race each other. It is short because a notice that
    // loses that race still takes the file over (adoptedFile): the wait saves a
    // loose row, it does not decide where the document ends up.
    suspend fun adoptLooseFiles(): Int = lock.withLock {
        val files = scan()
        val known = matched(load(), files)
        val claimed = known.mapNotNullTo(HashSet()) { it.filePath }
        val loose = files
            .filter { it.path !in claimed }
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
    // for the next pass.
    suspend fun classifyPending(read: (File) -> Classification?): Int {
        val pending = lock.withLock {
            load().filter { it.found && it.classification == null }.mapNotNull { it.filePath }
        }.distinct()
        if (pending.isEmpty()) return 0
        val verdicts = pending.mapNotNull { path -> read(File(path))?.let { path to it } }.toMap()
        lock.withLock {
            val known = load()
            save(
                known.map { sighting ->
                    val verdict = verdicts[sighting.filePath]
                    if (sighting.classification == null && verdict != null) {
                        sighting.copy(classification = verdict)
                    } else {
                        sighting
                    }
                },
            )
        }
        return verdicts.size
    }

    suspend fun clear() = lock.withLock { save(emptyList()) }

    // Loads the store into the flow, for a screen that opens before any notice.
    suspend fun open() = lock.withLock { load() }

    private fun matched(sightings: List<Sighting>, files: List<DocumentFile>): List<Sighting> {
        if (sightings.none { !it.found }) return sightings
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
                sighting.copy(filePath = it.path, fileName = it.name, fileSize = it.size)
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
    private fun resentFile(
        sighting: Sighting,
        earlier: List<Sighting>,
        files: List<DocumentFile>,
    ): DocumentFile? {
        if (now() - sighting.at < RESEND_SETTLE_MILLIS) return null
        val path = earlier.firstOrNull {
            it.found && it.app == sighting.app && it.chat == sighting.chat &&
                it.label == sighting.label
        }?.filePath ?: return null
        return files.firstOrNull { it.path == path }
    }

    private fun load(): List<Sighting> {
        state.value?.let { return it }
        val read = try {
            if (file.exists()) {
                STORE_JSON.decodeFromString<StoredSightings>(
                    file.readText()
                )
            } else {
                null
            }
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
        val sightings = read?.sightings.orEmpty()
        if (read == null) save(sightings) else state.value = sightings
        return sightings
    }

    private fun save(sightings: List<Sighting>) {
        val kept = sightings.sortedByDescending { it.at }.take(MAX_SIGHTINGS)
        state.value = kept
        try {
            file.writeText(STORE_JSON.encodeToString(StoredSightings(watchingSince, kept)))
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the sightings", e)
        }
    }

    private fun sightingOf(notice: DocumentNotice) = Sighting(
        key = notice.key,
        app = notice.app,
        chat = notice.chat,
        sender = notice.sender,
        phone = notice.phone,
        label = notice.label,
        at = notice.at,
        isGroup = notice.isGroup,
    )

    private fun looseSightingOf(file: DocumentFile) = Sighting(
        key = "file|${file.path}",
        app = WhatsAppDocuments.appOf(file.path),
        chat = "",
        sender = "",
        label = file.name,
        at = file.modifiedAt,
        filePath = file.path,
        fileName = file.name,
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

        const val RESEND_SETTLE_MILLIS = 60_000L

        const val LOOSE_SETTLE_MILLIS = 10_000L

        private val STORE_JSON = Json { ignoreUnknownKeys = true }
    }
}
