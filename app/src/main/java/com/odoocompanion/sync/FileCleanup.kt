package com.odoocompanion.sync

import android.util.Log
import com.odoocompanion.data.FileStamp
import com.odoocompanion.data.OutboxDao
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.data.QueuedFile
import com.odoocompanion.system.debug
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

internal class FileCleanup(
    private val dao: OutboxDao,
    private val remember: suspend (String, List<QueuedFile>) -> Unit,
) {
    suspend fun acknowledge(id: Long, now: Long) {
        // The database owns the fingerprint pinned just before the request.
        // Reading its receipt here also gives crash recovery the same file identity.
        finish(dao.acknowledgeFile(id, now), now)
    }

    suspend fun collect(now: Long): Int {
        val before = now - OutboxLimits.DEAD_RETENTION_MILLIS
        var removed = dao.deleteExpiredWithoutFiles(before)
        var afterId = 0L
        do {
            val batch = dao.filesForCleanup(before, afterId, PAGE_SIZE)
            if (batch.isEmpty()) break
            removed += finish(batch, now)
            afterId = batch.last().id
        } while (batch.size == PAGE_SIZE)
        if (removed > 0) debug(TAG) { "Cleaned up $removed terminal outbox rows" }
        return removed
    }

    private suspend fun finish(rows: List<QueuedFile>, now: Long): Int {
        val ready = rows.groupBy { it.kind }.flatMap { (kind, files) ->
            if (kind == OutboxKind.RECORDING) return@flatMap files
            try {
                remember(kind, files)
                files
            } catch (failure: IOException) {
                Log.w(
                    TAG,
                    "Retaining $kind receipts after history write failed (${failure.javaClass.simpleName})"
                )
                emptyList()
            }
        }
        if (ready.isEmpty()) return 0
        return dao.finishFileCleanup(ready, now) { row ->
            row.kind == OutboxKind.DOCUMENT || unlink(row)
        }
    }

    private fun unlink(row: QueuedFile): Boolean = try {
        val file = File(row.filePath)
        val current = FileStamp.read(file)
        val owned =
            row.kind != OutboxKind.RECORDING ||
                current?.matchesQueuedMetadata(row.source, row.createdAt) == true
        if (current != null && owned) {
            Files.delete(file.toPath())
        }
        true
    } catch (_: NoSuchFileException) {
        true
    } catch (failure: IOException) {
        Log.w(TAG, "Could not remove an outbox file (${failure.javaClass.simpleName})")
        false
    } catch (_: SecurityException) {
        Log.w(TAG, "Permission to remove an outbox file was denied")
        false
    }

    private companion object {
        const val TAG = "FileCleanup"
        const val PAGE_SIZE = 64
    }
}
