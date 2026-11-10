package com.odoocompanion.whatsapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

object ContactNumbers {
    const val WHATSAPP_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    const val WHATSAPP_BUSINESS_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"

    private const val SAME_LINE_DIGITS = 10

    fun choose(whatsappIds: List<String>, phones: List<String>): String? =
        onlyLine(whatsappIds.mapNotNull { WhatsAppNotices.phoneOf(it, null) })
            ?: onlyLine(phones.map { it.filter(Char::isDigit) })

    private fun onlyLine(numbers: List<String>): String? {
        val lines = numbers.filter { it.length >= SAME_LINE_DIGITS }
            .groupBy { it.takeLast(SAME_LINE_DIGITS) }
        return lines.values.singleOrNull()?.maxBy { it.length }
    }
}

class ContactBook(private val context: Context) {
    val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun numberOf(name: String): String? {
        if (!granted || name.isBlank()) return null
        return try {
            ContactNumbers.choose(
                whatsappIds = column(
                    ContactsContract.Data.CONTENT_URI,
                    ContactsContract.Data.DATA1,
                    "${ContactsContract.Data.DISPLAY_NAME} = ? AND " +
                        "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
                    arrayOf(
                        name,
                        ContactNumbers.WHATSAPP_PROFILE,
                        ContactNumbers.WHATSAPP_BUSINESS_PROFILE,
                    ),
                ),
                phones = column(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                    arrayOf(name),
                ),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Contacts were refused", e)
            null
        }
    }

    private fun column(
        uri: android.net.Uri,
        name: String,
        where: String,
        args: Array<String>,
    ): List<String> = context.contentResolver
        .query(uri, arrayOf(name), where, args, null)
        ?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
            }
        }
        .orEmpty()

    private companion object {
        const val TAG = "ContactBook"
    }
}
