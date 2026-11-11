package com.odoocompanion.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

// Light, dark, or the system's choice. Kept in the app's own preferences
// rather than in the device configuration: it is how this phone looks to the
// person holding it, never managed by an MDM and never sent to Odoo.
enum class Appearance(val nightMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        private const val FILE = "appearance"
        private const val KEY = "mode"

        fun current(context: Context): Appearance {
            val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY, null)
            return entries.firstOrNull { it.name == stored } ?: SYSTEM
        }

        fun choose(context: Context, appearance: Appearance) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit { putString(KEY, appearance.name) }
            AppCompatDelegate.setDefaultNightMode(appearance.nightMode)
        }

        // At startup, before the first screen is drawn in the wrong one.
        fun apply(context: Context) =
            AppCompatDelegate.setDefaultNightMode(current(context).nightMode)
    }
}
