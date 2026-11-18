package com.odoocompanion.push

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

// The connector echoes the instance in each callback, even after a process restart.
internal class PushBinding(context: Context) {
    private val prefs = context.getSharedPreferences("push_binding", Context.MODE_PRIVATE)

    fun owns(enrollment: String?, instance: String): Boolean {
        val binding = prefs.all
        return enrollment != null && binding[OWNER] == enrollment && binding[INSTANCE] == instance
    }

    // Called under the configuration mutex, before saving the distributor. Releasing
    // its last instance can clear the connector's distributor selection.
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
