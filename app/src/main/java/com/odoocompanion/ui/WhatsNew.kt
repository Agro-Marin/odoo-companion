package com.odoocompanion.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.odoocompanion.R
import com.odoocompanion.databinding.DialogWhatsNewBinding

data class WhatsNewPage(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
)

object WhatsNew {
    val RELEASES: Map<Long, List<WhatsNewPage>> = mapOf(
        8L to listOf(
            WhatsNewPage(
                R.drawable.ic_brand_symbol,
                R.string.new_icon_title,
                R.string.new_icon_body
            ),
            WhatsNewPage(
                R.drawable.ic_scan_badge,
                R.string.new_scan_title,
                R.string.new_scan_body
            ),
            WhatsNewPage(
                R.drawable.ic_scan_badge,
                R.string.new_widget_title,
                R.string.new_widget_body
            ),
            WhatsNewPage(R.drawable.ic_update, R.string.new_update_title, R.string.new_update_body),
            WhatsNewPage(R.drawable.ic_settings, R.string.new_admin_title, R.string.new_admin_body),
        ),
        9L to listOf(
            WhatsNewPage(
                R.drawable.ic_scan_badge,
                R.string.new_lock_widget_title,
                R.string.new_lock_widget_body,
            ),
        ),
    )

    fun pagesSince(seen: Long, installed: Long): List<WhatsNewPage> = RELEASES
        .filterKeys { it in (seen + 1)..installed }
        .toSortedMap()
        .values
        .flatten()

    fun seen(context: Context): Long = prefs(context).getLong(KEY_SEEN, 0)

    fun markSeen(context: Context, versionCode: Long) =
        prefs(context).edit { putLong(KEY_SEEN, versionCode) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "whats_new"
    private const val KEY_SEEN = "seen_version_code"
}

class WhatsNewDialog(private val context: Context, private val installed: Long) {
    fun showIfDue() {
        val pages = WhatsNew.pagesSince(WhatsNew.seen(context), installed)
        if (pages.isEmpty()) {
            WhatsNew.markSeen(context, installed)
            return
        }
        show(pages)
    }

    @SuppressLint("SetTextI18n")
    private fun show(pages: List<WhatsNewPage>) {
        val view = DialogWhatsNewBinding.inflate(LayoutInflater.from(context))
        var index = 0
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view.root)
            .setNegativeButton(R.string.whats_new_skip, null)
            .setPositiveButton(R.string.whats_new_next, null)
            .setCancelable(false)
            .setOnDismissListener { WhatsNew.markSeen(context, installed) }
            .create()

        fun render() {
            val page = pages[index]
            view.whatsNewIcon.setImageResource(page.icon)
            view.whatsNewTitle.setText(page.title)
            view.whatsNewBody.setText(page.body)
            view.whatsNewStep.text =
                context.getString(R.string.whats_new_step, index + 1, pages.size)
            val last = index == pages.lastIndex
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                ?.setText(if (last) R.string.whats_new_done else R.string.whats_new_next)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.visibility =
                if (last) android.view.View.GONE else android.view.View.VISIBLE
        }

        dialog.setOnShowListener {
            render()
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (index == pages.lastIndex) {
                    dialog.dismiss()
                } else {
                    index++
                    render()
                }
            }
        }
        dialog.show()
    }
}
