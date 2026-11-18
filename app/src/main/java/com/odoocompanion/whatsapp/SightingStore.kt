package com.odoocompanion.whatsapp

import android.util.AtomicFile
import android.util.Log
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.data.UploadState
import com.odoocompanion.data.writeHistory
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
import java.nio.file.Files

@Serializable
data class Sighting(
    val app: String,
    val chat: String,
    val sender: String,
    val phone: String? = null,
    val label: String,
    val at: Long,
    val isGroup: Boolean = false,
    val filePath: String? = null,
    val fileSize: Long = 0,
    val loose: Boolean = false,
    val classification: Classification? = null,
    val queued: Boolean = false,
    val readFailures: Int = 0,
    val missing: Boolean = false,
    val outcome: UploadState? = null,
) {
    val found: Boolean
        get() = filePath != null

    val unreadable: Boolean
        get() = found && classification == null && readFailures >= MAX_READ_ATTEMPTS

    val fileVersion get() = filePath to classification?.source

    fun queueRow(rowsByPath: Map<String, List<QueuedFileState>>): QueuedFileState? {
        val candidates = rowsByPath[filePath] ?: return null
        val source = classification?.source
        return candidates.lastOrNull { it.source == source } ?: candidates.lastOrNull {
            it.source == null && source != null && source.matchesQueuedMetadata(null, it.createdAt)
        }
    }

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
    val watchingSince: Long,
    val sightings: List<Sighting> = emptyList(),
    val retired: List<String> = emptyList(),
)

class SightingStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val scan: () -> List<DocumentFile>,
) {
    private val lock = Mutex()

    private val reading = Mutex()

    private val state = MutableStateFlow<List<Sighting>?>(null)

    private var watchingSince = 0L

    private var retired = emptyList<String>()

    private var writePending = false

    val sightings: StateFlow<List<Sighting>?> = state.asStateFlow()

    suspend fun record(notices: List<DocumentNotice>): Int = lock.withLock {
        val known = load() ?: return@withLock 0
        val fresh = mutableListOf<Sighting>()
        for (notice in notices) {
            if (known.none { it.sameDocumentAs(notice) } &&
                fresh.none { it.sameDocumentAs(notice) }
            ) {
                fresh += sightingOf(notice)
            }
        }
        save(matched(fresh + known, scan))
        fresh.size
    }

    suspend fun rematch() = lock.withLock {
        val known = load() ?: return@withLock
        save(matched(known, scan))
    }

    suspend fun adoptLooseFiles(): Int = lock.withLock {
        val files = scan()
        val known = matched(load() ?: return@withLock 0) { files }
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

    suspend fun fillNumbers(numberOf: (String) -> String?) = lock.withLock {
        val known = load() ?: return@withLock
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

    suspend fun classifyPending(read: (File) -> Classification?): Int = reading.withLock classify@{
        val all = lock.withLock { load() } ?: return@classify 0
        val pending = all.filter { it.classification == null && !it.unreadable }
            .mapNotNullTo(HashSet()) { it.filePath }
        if (pending.isEmpty()) return@classify 0
        val onDisk = scan().filter { it.path in pending }.associateBy { it.path }
        val verdicts = all.mapNotNull { sighting ->
            val path = sighting.filePath ?: return@mapNotNull null
            val file = onDisk[path] ?: return@mapNotNull null
            sighting.classification?.takeIf { file.matches(it.source) }?.let { path to it }
        }.toMap().toMutableMap()
        var reads = 0
        for ((path, file) in onDisk) {
            if (path in verdicts) continue
            read(File(path))?.let {
                verdicts[path] = if (it.source == null) it.copy(source = file.stamp) else it
                reads++
            }
        }
        lock.withLock {
            val known = requireNotNull(state.value)
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
        reads
    }

    suspend fun toUpload(): List<Sighting> = lock.withLock {
        load().orEmpty().filter {
            it.found && !it.queued && it.outcome == null && it.classification?.kind?.uploads == true
        }
    }

    suspend fun markQueued(ready: List<Sighting>) = lock.withLock {
        val known = load() ?: return@withLock
        val versions = ready.mapTo(HashSet()) { it.fileVersion }
        save(
            known.map {
                if (!it.queued &&
                    it.fileVersion in versions
                ) {
                    it.copy(queued = true)
                } else {
                    it
                }
            }
        )
    }

    suspend fun remember(receipts: List<QueuedFileState>) = lock.withLock {
        val known = load() ?: throw IOException("Document history unavailable")
        val byPath = receipts.groupBy { it.filePath }
        save(
            known.map { sighting ->
                sighting.queueRow(byPath)?.let {
                    sighting.copy(
                        queued = true,
                        outcome = UploadState.fromDeadReason(it.deadReason)
                    )
                } ?: sighting
            }
        )
        if (writePending) throw IOException("Document history is waiting for storage")
    }

    suspend fun open() = lock.withLock { load() }

    private fun matched(
        sightings: List<Sighting>,
        scan: () -> List<DocumentFile>,
    ): List<Sighting> {
        if (sightings.none { !it.found }) return sightings
        val files = scan()
        val claimed = sightings.mapNotNullTo(HashSet()) { it.filePath }
        val classified = sightings.filter { it.classification != null }.groupBy { it.filePath }
        val loosePaths = sightings.filter { it.loose }.mapNotNullTo(HashSet()) { it.filePath }
        val adopted = mutableSetOf<String>()
        val result = mutableListOf<Sighting>()
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
            result += file?.let { found ->
                val prior = classified[found.path]?.firstOrNull {
                    found.matches(it.classification?.source)
                }
                sighting.copy(
                    filePath = found.path,
                    fileSize = found.size,
                    classification = prior?.classification,
                    queued = prior?.queued == true,
                    outcome = prior?.outcome,
                )
            } ?: sighting
        }
        debug(TAG) { "${result.count { it.found }} of ${result.size} sighting(s) matched" }
        return result
    }

    private fun adoptedFile(
        notice: DocumentNotice,
        files: List<DocumentFile>,
        loosePaths: Set<String>,
    ): DocumentFile? {
        if (loosePaths.isEmpty()) return null
        return DocumentMatcher.match(notice, files.filter { it.path in loosePaths }, emptySet())
    }

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
        return file.takeIf { cameWithItsNotice(it, first) || cameWithItsNotice(it, sighting) }
    }

    private fun cameWithItsNotice(file: DocumentFile, sighting: Sighting): Boolean =
        file.modifiedAt - sighting.at in
            -DocumentMatcher.BEFORE_NOTICE_MILLIS..RESEND_SETTLE_MILLIS

    private fun load(): List<Sighting>? {
        state.value?.let { return it }
        val read = try {
            STORE_JSON.decodeFromString<StoredSightings>(
                AtomicFile(file).readFully().decodeToString(),
            )
        } catch (failure: FileNotFoundException) {
            if (!Files.notExists(file.toPath()) && file.parentFile?.isFile != true) {
                Log.w(TAG, "Could not open the sightings (${failure.javaClass.simpleName})")
                return null
            }
            null
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the sightings (${e.javaClass.simpleName})")
            return null
        } catch (e: SerializationException) {
            Log.w(TAG, "Could not decode the sightings; starting over (${e.javaClass.simpleName})")
            null
        }
        watchingSince = read?.watchingSince ?: now()
        retired = read?.retired.orEmpty()
        val sightings = read?.sightings.orEmpty().map { sighting ->
            val verdict = sighting.classification
            if (!sighting.queued && sighting.outcome == null &&
                verdict?.kind?.uploads == true && verdict.source?.sha256 == null &&
                (verdict.source?.size ?: 0) <= OutboxLimits.MAX_FILE_BYTES
            ) {
                // Older history never tied its verdict to the bytes being queued.
                sighting.copy(classification = null, readFailures = 0)
            } else {
                sighting
            }
        }
        if (read == null) save(sightings) else state.value = sightings
        return sightings
    }

    private fun save(sightings: List<Sighting>) {
        val ordered = sightings.sortedByDescending { it.at }
        val kept = ordered.take(MAX_SIGHTINGS)
        val trimmed = ordered.drop(MAX_SIGHTINGS).mapNotNull { it.filePath }
        if (!writePending && kept == state.value && trimmed.all { it in retired }) return
        retired = (retired - trimmed.toSet() + trimmed).takeLast(MAX_RETIRED)
        state.value = kept
        writePending = true
        try {
            val stored = StoredSightings(watchingSince, kept, retired)
            file.writeHistory(STORE_JSON.encodeToString(stored))
            writePending = false
        } catch (failure: IOException) {
            Log.w(TAG, "Could not write the sightings (${failure.javaClass.simpleName})")
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

        const val MAX_RETIRED = 500

        const val RESEND_SETTLE_MILLIS = 60_000L

        const val LOOSE_SETTLE_MILLIS = 10_000L

        private val STORE_JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }
}
