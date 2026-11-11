package com.odoocompanion.whatsapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.odoocompanion.R
import com.odoocompanion.net.DocumentMetadata
import com.odoocompanion.ui.MainActivity
import com.odoocompanion.ui.SettingsActivity

// What a notice says about a WhatsApp document that reached Odoo: what it
// is, which file, and the chat it came from.
data class UploadedDocument(
    val kind: DocumentKind?,
    val fileName: String,
    val rfc: String?,
    val chat: String?,
)

// The notice posted once Odoo took a document the handset read in WhatsApp,
// so the person holding the phone knows it went without opening the app.
object UploadedNotice {
    const val CHANNEL_ID = "documents_uploaded"

    private const val GROUP = "documents_uploaded"
    private const val SUMMARY_ID = 0x0D0C

    fun of(metadata: DocumentMetadata): UploadedDocument = UploadedDocument(
        kind = DocumentKind.entries.firstOrNull {
            it.name.equals(metadata.kind, ignoreCase = true)
        },
        fileName = metadata.fileName,
        rfc = metadata.rfc,
        chat = metadata.chat,
    )

    // One id per document, the same however often it is computed, so a
    // notice is replaced rather than stacked if the same one comes again.
    fun idOf(metadata: DocumentMetadata): Int =
        (metadata.fileName + metadata.receivedAt).hashCode() and Int.MAX_VALUE

    fun post(context: Context, delivered: List<DocumentMetadata>) {
        if (delivered.isEmpty() || !allowed(context)) return
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val documents = delivered.map { idOf(it) to of(it) }
        documents.forEach { (id, document) ->
            val notice = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_document)
                .setColor(ContextCompat.getColor(context, R.color.xiuman_fertil))
                .setContentTitle(title(context, document))
                .setContentText(detail(context, document))
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail(context, document)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .build()
            notify(manager, id, notice)
        }
        if (documents.size > 1) {
            val count = context.resources.getQuantityString(
                R.plurals.notification_documents_summary,
                documents.size,
                documents.size,
            )
            val inbox = NotificationCompat.InboxStyle().setSummaryText(count)
            documents.forEach { (_, document) -> inbox.addLine(title(context, document)) }
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_document)
                .setColor(ContextCompat.getColor(context, R.color.xiuman_fertil))
                .setContentTitle(count)
                .setStyle(inbox)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .build()
            notify(manager, SUMMARY_ID, summary)
        }
    }

    private fun title(context: Context, document: UploadedDocument): String {
        val kind = document.kind?.takeIf { it.uploads }
            ?.let { context.getString(SettingsActivity.kindName(it)) }
            ?: context.getString(R.string.notification_kind_document)
        return context.getString(R.string.notification_document_title, kind)
    }

    private fun detail(context: Context, document: UploadedDocument): String {
        val what = listOfNotNull(
            document.rfc?.let { context.getString(R.string.notification_document_rfc, it) },
            document.fileName,
        ).joinToString(" · ")
        return document.chat?.let {
            context.getString(R.string.notification_document_from, what, it)
        } ?: what
    }

    // Android 13+ asks for the permission, and every version lets the person
    // switch the app's notices off: either way nothing is posted.
    private fun allowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun notify(
        manager: NotificationManagerCompat,
        id: Int,
        notice: android.app.Notification,
    ) {
        try {
            manager.notify(id, notice)
        } catch (_: SecurityException) {
            // revoked between the check and the post
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_documents),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}
