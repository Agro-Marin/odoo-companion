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

class WhatsAppNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var watching: Job? = null

    @Volatile
    private var observer: FileObserver? = null

    private val arrivals = Channel<Unit>(Channel.CONFLATED)

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
        WhatsAppIntake.process(this@WhatsAppNotificationListener)
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
            WhatsAppIntake.process(this@WhatsAppNotificationListener)
            delay(RECHECK_MILLIS)
            store.rematch()
            WhatsAppIntake.process(this@WhatsAppNotificationListener)
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

        private fun shapeOf(key: String?): String = when {
            key == null -> "none"
            key.endsWith("@s.whatsapp.net") -> "jid"
            key.endsWith("@g.us") -> "group"
            key.endsWith("@lid") -> "lid"
            else -> "other"
        }

        private const val RECHECK_MILLIS = 75_000L

        private const val FOLDER_POLL_MILLIS = 5L * 60 * 1000

        @Volatile
        var connected = false
            private set

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
