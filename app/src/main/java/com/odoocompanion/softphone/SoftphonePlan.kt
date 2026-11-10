package com.odoocompanion.softphone

sealed interface SoftphonePlan {
    data class Run(val settings: SoftphoneSettings) : SoftphonePlan

    data object Stop : SoftphonePlan

    companion object {
        fun decide(
            enrolled: Boolean,
            lookup: SoftphoneLookup,
            stored: SoftphoneSettings?,
        ): SoftphonePlan = when {
            !enrolled -> Stop
            lookup is SoftphoneLookup.Found -> Run(lookup.settings)
            lookup is SoftphoneLookup.NoExtension -> Stop
            lookup is SoftphoneLookup.Refused -> Stop
            stored != null -> Run(stored)
            else -> Stop
        }
    }
}
