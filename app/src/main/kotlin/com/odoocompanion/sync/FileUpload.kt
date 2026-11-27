package com.odoocompanion.sync

import android.database.sqlite.SQLiteException
import android.util.Log
import com.odoocompanion.data.DeadReason
import com.odoocompanion.data.FileStamp
import com.odoocompanion.data.OutboxEntry
import com.odoocompanion.data.OutboxKind
import com.odoocompanion.data.OutboxLimits
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.net.FileBody
import com.odoocompanion.net.RecordingMetadata
import com.odoocompanion.net.RetryScope
import com.odoocompanion.net.UploadOutcome
import com.odoocompanion.net.WireJson
import com.odoocompanion.sync.OutboxDrainer.Outcome
import com.odoocompanion.system.debug
import com.odoocompanion.whatsapp.DocumentReader
import com.odoocompanion.whatsapp.QueuedDocument
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException

internal class FilePayload(val metadata: JsonObject, val document: QueuedDocument? = null)

internal class FileRoute private constructor(
    val kind: String,
    val suffix: String,
    val noun: String,
    val fileKey: String,
    val decode: (String, File, FileStamp) -> FilePayload,
) {
    companion object {
        val RECORDINGS = FileRoute(
            OutboxKind.RECORDING,
            "recording",
            "recording",
            FileBody.AUDIO_KEY,
        ) { payload, _, _ ->
            FilePayload(
                WireJson.encodeToJsonElement(
                    WireJson.decodeFromString<RecordingMetadata>(payload)
                ).jsonObject
            )
        }

        val DOCUMENTS = FileRoute(
            OutboxKind.DOCUMENT,
            "document",
            "document",
            FileBody.FILE_KEY,
        ) { payload, file, stamp ->
            val document = QueuedDocument.decode(payload).fromInbox(file.path, stamp.modifiedAt)
            FilePayload(WireJson.encodeToJsonElement(document.metadata).jsonObject, document)
        }

        val SCANS = FileRoute(
            OutboxKind.SCAN,
            "document",
            "scan",
            FileBody.FILE_KEY,
        ) { payload, _, _ ->
            FilePayload(
                WireJson.encodeToJsonElement(
                    WireJson.decodeFromString<DocumentMetadata>(payload)
                ).jsonObject
            )
        }
    }
}

internal suspend fun UploadSession.drainFiles(
    files: FileRoute,
    documentReader: DocumentReader,
    cleanup: FileCleanup,
): Outcome {
    while (true) {
        val entries =
            take(files.kind, OutboxDrainer.FILE_BATCH) ?: return Outcome.RETRY
        if (entries.isEmpty()) return Outcome.DONE
        for (entry in entries) {
            val file = entry.filePath?.let(::File)
            if (file == null) {
                forgetLostFile(entry, files)
                continue
            }
            val source = try {
                FileStamp.read(file)?.takeIf { current ->
                    entry.kind == OutboxKind.SCAN ||
                        current.matchesQueuedMetadata(entry.source, entry.createdAt)
                }
            } catch (_: NoSuchFileException) {
                forgetLostFile(entry, files)
                continue
            } catch (failure: IOException) {
                deferFile(
                    entry.id,
                    "file access failed (${failure.javaClass.simpleName})"
                )
                continue
            } catch (failure: SecurityException) {
                deferFile(
                    entry.id,
                    "file access failed (${failure.javaClass.simpleName})"
                )
                continue
            }
            if (source == null) {
                forgetLostFile(entry, files)
                continue
            }
            val size = source.size
            if (entry.kind == OutboxKind.RECORDING && entry.source == null &&
                dao.stampLegacyRecording(entry.id, size, source.modifiedAt) == 0
            ) {
                debug(DRAIN_TAG) { "Legacy recording row ${entry.id} changed before upload" }
                continue
            }
            if (size > OutboxLimits.MAX_FILE_BYTES) {
                deferRuledOn(
                    entry,
                    "${files.noun} of $size bytes is over the " +
                        "${OutboxLimits.MAX_FILE_BYTES / 1024 / 1024} MB any mobile device accepts",
                )
                continue
            }
            if (tally.bytesLeft <= 0) {
                tally.moreWork = true
                return Outcome.DONE
            }

            val payload = attempt {
                files.decode(entry.payload, file, source)
            }.getOrElse {
                abandon(
                    files.kind,
                    listOf(entry.id),
                    undecodableReason(listOf(entry)),
                    counter = tally.undecodable,
                    deadReason = DeadReason.UNDECODABLE,
                )
                continue
            }
            val body = FileBody(payload.metadata, file, fileKey = files.fileKey, stamp = source)
            val wireSize = body.contentLength()
            val declared = tally.payloadLimit
            debug(DRAIN_TAG) {
                "${files.noun} row ${entry.id}: $size bytes, " +
                    "$wireSize on the wire, cap $declared, " +
                    "attempt ${entry.attempts + 1}, ${tally.bytesLeft} budget left"
            }
            if (declared in 1..<wireSize) {
                deferRuledOn(
                    entry,
                    "${files.noun} would send $wireSize bytes and the server declared a " +
                        "${declared / 1024} KB cap, so it is not sent",
                )
                continue
            }
            val outcome = attempt {
                val document = payload.document
                if (document == null) {
                    post(files.suffix, body)
                } else {
                    documentReader.withVerifiedContent(
                        file,
                        size,
                        entry.source?.sha256 ?: entry.recheckedSha256,
                        document,
                        pinRechecked = { digest ->
                            if (dao.pinRecheckedDocument(entry.id, digest) == 0) {
                                throw IOException("Pending document changed before upload")
                            }
                        },
                    ) { input ->
                        post(files.suffix, body.fromVerifiedCopy(input))
                    }
                }
            }.getOrElse { failure ->
                if (failure is SQLiteException) {
                    val reason = "digest write failed (${failure.javaClass.simpleName})"
                    Log.w(DRAIN_TAG, reason)
                    tally.lastError = reason
                    return Outcome.RETRY
                }
                if (failure is IOException || failure is SecurityException) {
                    deferFile(
                        entry.id,
                        "file access failed (${failure.javaClass.simpleName})"
                    )
                    continue
                }
                return failBatch(listOf(entry.id), failure)
            }
            if (outcome == null) {
                forgetLostFile(entry, files)
                continue
            }
            debug(DRAIN_TAG) {
                "${files.noun} row ${entry.id} answered ${outcome.diagnostic()}"
            }
            when (outcome) {
                UploadOutcome.CredentialsUnavailable -> return credentialsUnavailable()

                is UploadOutcome.Success, is UploadOutcome.Duplicate -> {
                    val success = outcome as? UploadOutcome.Success
                    learn(success?.limitBytes)
                    if (success != null &&
                        (success.skipped > 0 || 0 in success.skippedIndexes)
                    ) {
                        abandon(
                            files.kind,
                            listOf(entry.id),
                            "server skipped the ${files.noun}"
                        )
                        continue
                    }
                    tally.delivered = true
                    if (success == null || success.duplicates > 0) {
                        tally.note(tally.duplicates, files.kind, 1)
                    } else {
                        tally.note(tally.accepted, files.kind, 1)
                        payload.document?.let(tally.deliveredDocuments::add)
                    }
                    cleanup.acknowledge(entry.id, now())
                }

                is UploadOutcome.Rejected -> abandon(
                    files.kind,
                    listOf(entry.id),
                    "server refused the ${files.noun} (${outcome.code})",
                )

                is UploadOutcome.TooLarge -> {
                    learn(outcome.limitBytes)
                    deferRuledOn(
                        entry,
                        "${files.noun} of $size bytes is ${outcome.describe()}",
                    )
                }

                is UploadOutcome.Retry -> {
                    if (outcome.scope == RetryScope.LOCAL_FILE) {
                        deferFile(entry.id, outcome.reason)
                        continue
                    }
                    if (outcome.scope != RetryScope.ROW) {
                        return chargeTheLink(
                            listOf(entry.id),
                            outcome.reason,
                            outcome.scope == RetryScope.LINK,
                        )
                    }
                    deferRuledOn(entry, outcome.reason)
                }
            }
        }
    }
}

private suspend fun UploadSession.forgetLostFile(entry: OutboxEntry, files: FileRoute) {
    val folder = entry.filePath.orEmpty().loggableDirectory()
    val reason = "its original file is no longer available under $folder"
    val verb = if (files.kind == OutboxKind.RECORDING) {
        dao.delete(listOf(entry.id))
        "Dropping"
    } else {
        dao.markDeadIds(listOf(entry.id), now(), reason, DeadReason.GONE)
        "Setting aside"
    }
    tally.lost++
    Log.w(DRAIN_TAG, "$verb ${files.noun} row ${entry.id}: $reason")
}
