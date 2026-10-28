package com.odoocompanion.whatsapp

// One message of a notification, as the listener read it off the platform
// objects: plain values, so what counts as a document is checkable without a
// NotificationListenerService or a StatusBarNotification.
data class NoticeMessage(
    val text: CharSequence?,
    val sender: String?,
    // Person.key and Person.uri, as WhatsApp fills them. Which of the two, if
    // either, carries the sender's number is exactly what this proposal has to
    // learn from a real handset.
    val senderKey: String? = null,
    val senderUri: String? = null,
    val time: Long = 0,
    // A message the handset's own user wrote: MessagingStyle gives it no person.
    val fromMe: Boolean = false,
)

data class NoticeFields(
    val packageName: String,
    val title: String?,
    val conversationTitle: String? = null,
    val isGroup: Boolean = false,
    // The "3 messages from 2 chats" bundle header, which repeats what its
    // children already say.
    val isSummary: Boolean = false,
    val messages: List<NoticeMessage>,
    val postedAt: Long,
    // The notification's shortcut id and tag. WhatsApp names a conversation by
    // its JID, so in a one-to-one chat these are where the number is when the
    // person on the message carries only a contact name.
    val chatKeys: List<String?> = emptyList(),
)

data class DocumentNotice(
    val app: String,
    val chat: String,
    val sender: String,
    val phone: String?,
    // The file name WhatsApp shows, or the caption when the sender wrote one.
    val label: String,
    val at: Long,
    val isGroup: Boolean,
) {
    // WhatsApp posts a conversation again with every new message, carrying the
    // unread ones before it: the same document arrives in several notices.
    val key: String
        get() = "$app|$chat|$label|$at"
}

object WhatsAppNotices {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    val PACKAGES = setOf(WHATSAPP, WHATSAPP_BUSINESS)

    // WhatsApp prefixes an attachment with an emoji naming its kind; a
    // document's is U+1F4C4, PAGE FACING UP.
    private const val DOCUMENT_MARK = "📄"

    // "Constancia.pdf (3 páginas)": the page count some builds append. The
    // spaces around it are not always plain ones: measured on a handset, the
    // count survived a pattern that knew only ASCII whitespace.
    private val PAGE_COUNT = Regex("""[\s\p{Z}]*\(\d+[\s\p{Z}]+\p{L}+\)$""")

    // Bidirectional marks WhatsApp may wrap a name in; invisible, but they
    // stand between the extension and the end of the text.
    private val INVISIBLE = Regex("""[\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF]""")

    private val JID_NUMBER = Regex("""^(\d{8,15})@s\.whatsapp\.net$""")

    fun documents(fields: NoticeFields): List<DocumentNotice> {
        if (fields.packageName !in PACKAGES || fields.isSummary) return emptyList()
        val chat = fields.conversationTitle ?: fields.title ?: return emptyList()
        val chatPhone = if (fields.isGroup) {
            null
        } else {
            fields.chatKeys.firstNotNullOfOrNull { phoneOf(it, null) }
        }
        return fields.messages
            .filterNot { it.fromMe }
            .mapNotNull { message ->
                val label = labelOf(message.text) ?: return@mapNotNull null
                DocumentNotice(
                    app = fields.packageName,
                    chat = chat,
                    sender = message.sender ?: fields.title ?: chat,
                    phone = phoneOf(message.senderKey, message.senderUri) ?: chatPhone,
                    label = label,
                    at = message.time.takeIf { it > 0 } ?: fields.postedAt,
                    isGroup = fields.isGroup,
                )
            }
            .distinctBy { it.key }
    }

    // The notices still without a number, given the one the handset's contacts
    // hold for the sender's name; each name is asked once.
    fun withNumbers(
        notices: List<DocumentNotice>,
        numberOf: (String) -> String?,
    ): List<DocumentNotice> {
        val asked = mutableMapOf<String, String?>()
        return notices.map { notice ->
            if (notice.phone != null) return@map notice
            notice.copy(phone = asked.getOrPut(notice.sender) { numberOf(notice.sender) })
        }
    }

    fun labelOf(text: CharSequence?): String? {
        val plain = text?.toString()?.replace(INVISIBLE, "")?.trim() ?: return null
        if (!plain.startsWith(DOCUMENT_MARK)) return null
        return plain.removePrefix(DOCUMENT_MARK).replace(PAGE_COUNT, "").trim()
            .takeIf { it.isNotBlank() }
    }

    // Digits only, from a tel: URI or a WhatsApp JID; anything else is a
    // contact name or an opaque key, and guessing a number from it would match
    // the wrong partner.
    fun phoneOf(key: String?, uri: String?): String? {
        uri?.takeIf { it.startsWith("tel:") }?.let { tel ->
            return tel.filter(Char::isDigit).takeIf { it.length >= MIN_DIGITS }
        }
        return key?.let { JID_NUMBER.matchEntire(it)?.groupValues?.get(1) }
    }

    private const val MIN_DIGITS = 8
}
