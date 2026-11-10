package com.odoocompanion.whatsapp

data class NoticeMessage(
    val text: CharSequence?,
    val sender: String?,
    val senderKey: String? = null,
    val senderUri: String? = null,
    val time: Long = 0,
    val fromMe: Boolean = false,
)

data class NoticeFields(
    val packageName: String,
    val title: String?,
    val conversationTitle: String? = null,
    val isGroup: Boolean = false,
    val isSummary: Boolean = false,
    val messages: List<NoticeMessage>,
    val postedAt: Long,
    val chatKeys: List<String?> = emptyList(),
)

data class DocumentNotice(
    val app: String,
    val chat: String,
    val sender: String,
    val phone: String?,
    val label: String,
    val at: Long,
    val isGroup: Boolean,
) {
    val key: String
        get() = "$app|$chat|$label|$at"
}

object WhatsAppNotices {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    val PACKAGES = setOf(WHATSAPP, WHATSAPP_BUSINESS)

    private const val DOCUMENT_MARK = "📄"

    private val PAGE_COUNT = Regex("""[\s\p{Z}]*\(\d+[\s\p{Z}]+\p{L}+\)$""")

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

    fun phoneOf(key: String?, uri: String?): String? {
        uri?.takeIf { it.startsWith("tel:") }?.let { tel ->
            return tel.filter(Char::isDigit).takeIf { it.length >= MIN_DIGITS }
        }
        return key?.let { JID_NUMBER.matchEntire(it)?.groupValues?.get(1) }
    }

    private const val MIN_DIGITS = 8
}
