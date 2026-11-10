package com.odoocompanion.ui

import com.odoocompanion.config.ManagedKey

enum class GuideField(val key: String) {
    BASE_URL(ManagedKey.BASE_URL),
    IDENTIFIER(ManagedKey.IDENTIFIER),
    TOKEN(ManagedKey.TOKEN),
}

data class GuideStep(val field: GuideField, val number: Int, val total: Int)

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
