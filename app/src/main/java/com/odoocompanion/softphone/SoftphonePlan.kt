package com.odoocompanion.softphone

/** Whether the softphone runs, and on which extension. */
sealed interface SoftphonePlan {
    data class Run(val settings: SoftphoneSettings) : SoftphonePlan

    data object Stop : SoftphonePlan

    companion object {
        /**
         * An enrolled handset runs the extension Odoo names; one Odoo says has
         * none stops, and so does one whose token Odoo refuses, since revoking
         * the token is how a lost handset is cut off. When Odoo could not be
         * asked, the last extension it named keeps running: an outage of Odoo
         * must not silence the company number.
         */
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
