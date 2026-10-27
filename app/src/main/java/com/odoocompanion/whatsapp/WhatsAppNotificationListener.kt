package com.odoocompanion.whatsapp

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.odoocompanion.CompanionApp
import com.odoocompanion.system.debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Reads WhatsApp's notices for the documents they announce. Every other app's
// notification is dropped at the first line; what counts as a document is
// WhatsAppNotices', so it can be tested without this service.
class WhatsAppNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var watching: Job? = null

    private val reader = PageReader()

    @Volatile
    private var observer: FileObserver? = null

    // A file finished in WhatsApp's folder; conflated, since a burst of them
    // needs one look, not one per file.
    private val arrivals = Channel<Unit>(Channel.CONFLATED)

    // WhatsApp posts nothing for a document sent into the chat on screen, so
    // the folder is watched too, for as long as the system keeps this listener
    // bound -- which it does for as long as notification access is on. A
    // FileObserver says when a file lands; the slow poll is for what it misses,
    // since Android/media sits behind FUSE from Android 11, and whether a write
    // by another app reaches inotify there differs between handsets.
    override fun onListenerConnected() {
        connected = true
        val store = CompanionApp.from(this).whatsapp
        stopWatching()
        watching = scope.launch {
            launch {
                while (isActive) {
                    arrivals.receive()
                    store.rematch()
                    delay(SightingStore.LOOSE_SETTLE_MILLIS)
                    adopt(store)
                }
            }
            while (isActive) {
                // WhatsApp makes its folder on its first download.
                if (observer == null) observer = folderObserver()?.also { it.startWatching() }
                adopt(store)
                delay(FOLDER_POLL_MILLIS)
            }
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        stopWatching()
    }

    private suspend fun adopt(store: SightingStore) {
        val loose = store.adoptLooseFiles()
        if (loose > 0) debug(TAG) { "$loose document(s) arrived without a notice" }
        store.classifyPending(reader::classify)
    }

    private fun folderObserver(): FileObserver? {
        val folders = WhatsAppDocuments(Environment.getExternalStorageDirectory())
            .watchedDirectories()
        debug(TAG) { "watching ${folders.size} folder(s)" }
        if (folders.isEmpty()) return null
        return object : FileObserver(folders, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                arrivals.trySend(Unit)
            }
        }
    }

    private fun stopWatching() {
        observer?.stopWatching()
        observer = null
        watching?.cancel()
        watching = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WhatsAppNotices.PACKAGES) return
        val fields = fieldsOf(sbn)
        val documents = WhatsAppNotices.documents(fields)
        debug(TAG) {
            "notice: ${fields.messages.size} message(s), ${documents.size} document(s), " +
                "summary=${fields.isSummary}, group=${fields.isGroup}, " +
                "keys=${fields.chatKeys.map(::shapeOf)}, " +
                "with number=${documents.count { it.phone != null }}"
        }
        if (documents.isEmpty()) return
        val store = CompanionApp.from(this).whatsapp
        val contacts = ContactBook(this)
        scope.launch {
            val numbered = WhatsAppNotices.withNumbers(documents, contacts::numberOf)
            debug(TAG) {
                "contacts granted=${contacts.granted}, " +
                    "numbered ${numbered.count { it.phone != null }} of ${numbered.size}"
            }
            store.record(numbered)
            store.classifyPending(reader::classify)
            // An automatic download may land just after its notice.
            delay(RECHECK_MILLIS)
            store.rematch()
            store.classifyPending(reader::classify)
        }
    }

    override fun onDestroy() {
        connected = false
        stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    private fun fieldsOf(sbn: StatusBarNotification): NoticeFields {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val messages = style?.messages?.map {
            NoticeMessage(
                text = it.text,
                sender = it.person?.name?.toString(),
                senderKey = it.person?.key,
                senderUri = it.person?.uri,
                time = it.timestamp,
                fromMe = it.person == null,
            )
        } ?: listOf(
            NoticeMessage(
                text = extras.getCharSequence(Notification.EXTRA_TEXT),
                sender = title,
                time = sbn.postTime,
            ),
        )
        return NoticeFields(
            packageName = sbn.packageName,
            title = title,
            conversationTitle = style?.conversationTitle?.toString(),
            isGroup = style?.isGroupConversation == true,
            isSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            messages = messages,
            postedAt = sbn.postTime,
            chatKeys = listOf(notification.shortcutId, sbn.tag),
        )
    }

    companion object {
        private const val TAG = "WhatsAppListener"

        // What kind of key it is, never the key: a JID is a phone number.
        private fun shapeOf(key: String?): String = when {
            key == null -> "none"
            key.endsWith("@s.whatsapp.net") -> "jid"
            key.endsWith("@g.us") -> "group"
            key.endsWith("@lid") -> "lid"
            else -> "other"
        }

        // Past SightingStore.RESEND_SETTLE_MILLIS, so the recheck can also
        // settle a resend WhatsApp saved no new file for.
        private const val RECHECK_MILLIS = 75_000L

        // The fallback for an arrival the observer did not hear, and for a
        // WhatsApp folder created after the listener connected.
        private const val FOLDER_POLL_MILLIS = 5L * 60 * 1000

        // Whether the system has this listener bound right now. Access being on
        // is not enough: measured on a handset, after the app was updated over
        // itself the listener stayed unbound with access still on, and nothing
        // was watched while the card said everything was granted.
        @Volatile
        var connected = false
            private set

        // Asks the system to bind the listener again; a no-op when it is bound.
        fun reconnect(context: Context) {
            if (!connected && isEnabled(context)) {
                NotificationListenerService.requestRebind(component(context))
            }
        }

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) =
            ComponentName(context, WhatsAppNotificationListener::class.java)
    }
}
