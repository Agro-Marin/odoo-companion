package com.odoocompanion.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

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

        fun apply(context: Context) =
            AppCompatDelegate.setDefaultNightMode(current(context).nightMode)
    }
}
