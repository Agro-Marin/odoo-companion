package com.odoocompanion.push

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

internal class PushBinding(context: Context) {
    private val prefs = context.getSharedPreferences("push_binding", Context.MODE_PRIVATE)

    fun owns(enrollment: String?, instance: String): Boolean {
        val binding = prefs.all
        return enrollment != null && binding[OWNER] == enrollment && binding[INSTANCE] == instance
    }

    fun activate(enrollment: String, release: (String) -> Unit): String {
        val previous = prefs.getString(INSTANCE, LEGACY).orEmpty()
        if (owns(enrollment, previous)) return previous
        if (previous.isNotEmpty()) release(previous)
        val instance = UUID.randomUUID().toString()
        prefs.edit {
            putString(OWNER, enrollment)
            putString(INSTANCE, instance)
        }
        return instance
    }

    fun disable(release: (String) -> Unit) {
        val previous = prefs.getString(INSTANCE, LEGACY).orEmpty()
        if (previous.isEmpty()) return
        release(previous)
        prefs.edit {
            remove(OWNER)
            putString(INSTANCE, "")
        }
    }

    private companion object {
        const val INSTANCE = "instance"
        const val OWNER = "owner"
        const val LEGACY = "default"
    }
}
