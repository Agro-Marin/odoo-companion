package com.odoocompanion.ui

import com.odoocompanion.config.ManagedKey

// The three values that enrol a phone, in the order they are filled in.
enum class GuideField(val key: String) {
    BASE_URL(ManagedKey.BASE_URL),
    IDENTIFIER(ManagedKey.IDENTIFIER),
    TOKEN(ManagedKey.TOKEN),
}

data class GuideStep(val field: GuideField, val number: Int, val total: Int)

// Which empty field the guide points at, decided away from the views: the
// form calls it on every keystroke, so a field emptied later brings its step
// back, and a field a policy manages is never asked of the person holding
// the phone.
object EnrollmentGuide {
    fun pending(values: Map<GuideField, String>, governed: Set<String>): List<GuideField> =
        GuideField.entries.filter { it.key !in governed && values[it].isNullOrBlank() }

    fun current(
        values: Map<GuideField, String>,
        governed: Set<String>,
        dismissed: Set<GuideField>,
    ): GuideStep? {
        val pending = pending(values, governed)
        val field = pending.firstOrNull { it !in dismissed } ?: return null
        return GuideStep(field, pending.indexOf(field) + 1, pending.size)
    }
}
