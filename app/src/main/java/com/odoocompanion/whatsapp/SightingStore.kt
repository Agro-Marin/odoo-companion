package com.odoocompanion.whatsapp

import android.util.AtomicFile
import android.util.Log
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueuedFileState
import com.odoocompanion.data.UploadState
import com.odoocompanion.data.rowFor
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
    val receiptsAwaitingClassification: List<QueuedFileState> = emptyList(),
    val hasMessageTime: Boolean = false,
) {
    val found: Boolean
        get() = filePath != null

    val unreadable: Boolean
        get() = found && classification == null && readFailures >= MAX_READ_ATTEMPTS

    val fileVersion get() = filePath to classification?.source

    internal val awaitingUpload: Boolean
        get() = found && !queued && outcome == null && classification?.kind?.uploads == true

    fun queueRow(rowsByPath: Map<String, List<QueuedFileState>>): QueuedFileState? =
        rowsByPath[filePath]?.rowFor(classification?.source)

    internal fun uploadState(row: QueuedFileState?): UploadState =
        if (outcome == UploadState.DELIVERED && classification?.source?.sha256 != null &&
            row?.deadReason != null
        ) {
            UploadState.DELIVERED
        } else {
            UploadState.forFile(row, outcome)
        }

    internal fun withReceipts(receipts: List<QueuedFileState>): Sighting {
        val candidates = if (receiptsAwaitingClassification.isEmpty()) {
            receipts
        } else {
            (receiptsAwaitingClassification + receipts).distinct()
        }
        if (candidates.isEmpty()) return this
        val source = classification?.source
        val deferred = if (source?.sha256 == null) {
            candidates.filter { it.source?.sha256 != null || it.recheckedSha256 != null }
        } else {
            emptyList()
        }
        val row = if (classification == null &&
            deferred.isNotEmpty()
        ) {
            null
        } else {
            candidates.rowFor(source)
        }
        return copy(
            queued = queued || row != null,
            outcome = if (row == null) outcome else uploadState(row),
            receiptsAwaitingClassification = deferred,
        )
    }

    internal fun sameAttachmentAs(notice: DocumentNotice): Boolean {
        if (app != notice.app || chat != notice.chat || label != notice.label ||
            isGroup != notice.isGroup
        ) {
            return false
        }
        if (phone != null && notice.phone != null) return phone == notice.phone
        return !isGroup || (phone == notice.phone && sender == notice.sender)
    }

    fun sameDocumentAs(notice: DocumentNotice): Boolean =
        !loose && sameAttachmentAs(notice) && if (hasMessageTime && notice.hasMessageTime) {
            at == notice.at
        } else {
            kotlin.math.abs(at - notice.at) <= SAME_MESSAGE_MILLIS
        }

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
        val rows = known.toMutableList()
        var added = 0
        for (notice in notices) {
            val index = rows.indexOfFirst { it.sameDocumentAs(notice) }
            if (index < 0) {
                rows.add(added, sightingOf(notice))
                added++
            } else {
                val prior = rows[index]
                val timed = notice.hasMessageTime && !prior.hasMessageTime
                if (timed || (prior.phone == null && notice.phone != null)) {
                    rows[index] = prior.copy(
                        at = if (timed) notice.at else prior.at,
                        hasMessageTime = prior.hasMessageTime || notice.hasMessageTime,
                        phone = prior.phone ?: notice.phone,
                    )
                }
            }
        }
        save(matched(rows, scan))
        added
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
        val lookup = ContactNumbers.cachedLookup(numberOf)
        val filled = known.map { sighting ->
            if (sighting.phone != null || sighting.loose || sighting.sender.isBlank()) {
                return@map sighting
            }
            val phone = lookup(sighting.sender)
            if (phone == null) sighting else sighting.copy(phone = phone)
        }
        if (filled != known) save(filled)
    }

    suspend fun classifyPending(read: (File) -> Classification?): Int = reading.withLock classify@{
        val all = lock.withLock { load() } ?: return@classify 0
        val pending = all.filter { it.found && it.classification == null && !it.unreadable }
            .groupBy { it.filePath }
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
                val notice = noticeOf(sighting)
                val observed = pending[path]?.any {
                    it.at == sighting.at && it.loose == sighting.loose &&
                        it.sameAttachmentAs(notice)
                } == true
                val verdict = verdicts[path]
                when {
                    !observed || sighting.classification != null -> sighting
                    verdict != null -> sighting.copy(classification = verdict, missing = false)
                    path !in onDisk -> sighting.copy(missing = true)
                    else -> sighting.copy(readFailures = sighting.readFailures + 1, missing = false)
                }
            }
            if (classified != known) save(reconcileVerifiedFiles(classified))
        }
        reads
    }

    internal suspend fun enqueuePending(enqueue: suspend (List<Sighting>) -> Int): Int =
        lock.withLock {
            val known = load() ?: return@withLock 0
            val ready = known.filter { it.awaitingUpload }
            if (ready.isEmpty()) {
                if (writePending) save(known)
                return@withLock 0
            }
            val count = enqueue(ready)
            save(known.map { if (it.awaitingUpload) it.copy(queued = true) else it })
            count
        }

    suspend fun remember(receipts: List<QueuedFileState>) = lock.withLock {
        val known = load() ?: throw IOException("Document history unavailable")
        val byPath = receipts.groupBy { it.filePath }
        save(
            known.map { sighting ->
                byPath[sighting.filePath]?.let(sighting::withReceipts) ?: sighting
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
        val named = sightings.filterNot { it.loose }.mapNotNullTo(HashSet()) { it.filePath }
        val loosePaths = (claimed - named).toMutableSet()
        val adopted = mutableMapOf<String, DocumentFile>()
        val result = mutableListOf<Sighting>()
        for (simultaneous in sightings.groupBy { it.at }.toSortedMap().values) {
            val (found, pending) = simultaneous.partition { it.found }
            result += found
            val choices = pending.map { sighting ->
                val notice = noticeOf(sighting)
                val candidates = DocumentMatcher.closest(notice, files, claimed)
                    .ifEmpty { looseCandidates(notice, files, loosePaths) }
                    .ifEmpty { listOfNotNull(resentFile(notice, result, files)) }
                sighting to candidates
            }
            val claims = choices.flatMap { it.second }.groupingBy { it.path }.eachCount()
            for ((sighting, candidates) in choices) {
                val file = candidates.singleOrNull()?.takeIf { claims[it.path] == 1 }
                if (file == null) {
                    result += sighting
                    continue
                }
                claimed += file.path
                if (loosePaths.remove(file.path)) adopted[file.path] = file
                val prior = classified[file.path]?.firstOrNull {
                    file.matches(it.classification?.source)
                }
                result += sighting.copy(
                    filePath = file.path,
                    fileSize = file.size,
                    classification = prior?.classification,
                    queued = prior?.queued == true,
                    outcome = prior?.outcome,
                )
            }
        }
        val matched = result.filterNot { sighting ->
            val current = adopted[sighting.filePath]
            sighting.loose && current != null && sighting.canHandOver(current)
        }
        debug(TAG) { "${matched.count { it.found }} of ${matched.size} sighting(s) matched" }
        return matched
    }

    private fun Sighting.canHandOver(current: DocumentFile): Boolean {
        val source = classification?.source
        return if (source?.sha256 != null) {
            current.matches(source)
        } else {
            !queued && outcome == null && receiptsAwaitingClassification.isEmpty()
        }
    }

    private fun looseCandidates(
        notice: DocumentNotice,
        files: List<DocumentFile>,
        loosePaths: Set<String>,
    ): List<DocumentFile> {
        if (loosePaths.isEmpty()) return emptyList()
        return DocumentMatcher.closest(notice, files.filter { it.path in loosePaths }, emptySet())
    }

    private fun resentFile(
        notice: DocumentNotice,
        earlier: List<Sighting>,
        files: List<DocumentFile>,
    ): DocumentFile? {
        if (now() - notice.at < RESEND_SETTLE_MILLIS) return null
        val prior = earlier.filter { it.found && !it.loose && it.sameAttachmentAs(notice) }
            .groupBy { it.filePath }
        val candidates = files.filter { file ->
            val previous = prior[file.path]
            file.app == notice.app && previous != null &&
                (cameWithNotice(file, notice.at) || previous.any { cameWithNotice(file, it.at) })
        }
        val rewritten = candidates.filter { cameWithNotice(it, notice.at) }
        return rewritten.ifEmpty { candidates }.singleOrNull()
    }

    private fun cameWithNotice(file: DocumentFile, at: Long): Boolean =
        file.modifiedAt - at in -DocumentMatcher.BEFORE_NOTICE_MILLIS..RESEND_SETTLE_MILLIS

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
        val stored = read?.sightings.orEmpty()
        val sightings = stored.flatMap(::restoreInbox).map { sighting ->
            val verdict = sighting.classification
            if (!sighting.queued && sighting.outcome == null &&
                verdict?.kind?.uploads == true && verdict.source?.sha256 == null &&
                (verdict.source?.size ?: 0) <= OutboxLimits.MAX_FILE_BYTES
            ) {
                sighting.copy(classification = null, readFailures = 0)
            } else {
                sighting
            }
        }
        val reconciled = reconcileVerifiedFiles(sightings)
        if (read == null || reconciled != stored) save(reconciled) else state.value = stored
        return state.value
    }

    private fun reconcileVerifiedFiles(sightings: List<Sighting>): List<Sighting> {
        val deferred = sightings.flatMap {
            it.receiptsAwaitingClassification
        }.groupBy { it.filePath }
        val resolved = if (deferred.isEmpty()) {
            sightings
        } else {
            sightings.map { sighting ->
                val receipts = deferred[sighting.filePath]
                if (sighting.classification?.source?.sha256 != null && receipts != null) {
                    sighting.withReceipts(receipts)
                } else {
                    sighting
                }
            }
        }
        val aliases = resolved.filter { it.found && it.classification?.source?.sha256 != null }
            .groupBy { it.fileVersion }.filterValues { it.size > 1 }
        if (aliases.isEmpty()) return resolved
        val receipts = aliases.mapValues { (_, rows) ->
            rows.firstOrNull { it.outcome == UploadState.DELIVERED }
                ?: rows.firstOrNull { it.outcome != null } ?: rows.firstOrNull { it.queued }
        }
        val represented = aliases.filterValues { rows ->
            rows.any { !it.loose }
        }.keys.toMutableSet()
        return resolved.mapNotNull { sighting ->
            val version = sighting.fileVersion
            if (sighting.loose && version in aliases && !represented.add(version)) {
                return@mapNotNull null
            }
            val receipt = receipts[version] ?: return@mapNotNull sighting
            sighting.copy(queued = true, outcome = receipt.outcome)
        }
    }

    private fun restoreInbox(sighting: Sighting): List<Sighting> {
        val path = sighting.filePath
        val owner = path?.let(WhatsAppInbox::containing)
        if (owner == null || owner.app == sighting.app) return listOf(sighting)
        if (sighting.loose) return listOf(sighting.copy(app = owner.app))

        val notice = sightingOf(noticeOf(sighting))
        val modifiedAt = sighting.classification?.source?.modifiedAt
        if (!sighting.queued && sighting.outcome == null &&
            sighting.receiptsAwaitingClassification.isEmpty() &&
            (modifiedAt == null || modifiedAt < watchingSince)
        ) {
            return listOf(notice)
        }
        val file = DocumentFile(
            path,
            File(path).name,
            modifiedAt ?: sighting.at,
            sighting.fileSize,
            owner.app,
        )
        return listOf(
            notice,
            looseSightingOf(file).copy(
                classification = sighting.classification,
                queued = sighting.queued,
                outcome = sighting.outcome,
                receiptsAwaitingClassification = sighting.receiptsAwaitingClassification,
            ),
        )
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
        hasMessageTime = notice.hasMessageTime,
    )

    private fun looseSightingOf(file: DocumentFile) = Sighting(
        app = file.app,
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
        hasMessageTime = sighting.hasMessageTime,
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
