package com.odoocompanion.softphone

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The last softphone settings Odoo handed out, so the softphone keeps
 * registering through a restart or an outage of Odoo itself.
 */
class SoftphoneStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(storeFor(context))

    /** Whether Odoo has handed this handset an extension, as the status screen asks. */
    val configured: Flow<Boolean> = store.data.map { it[USERNAME] != null }.distinctUntilChanged()

    suspend fun current(): SoftphoneSettings? {
        val prefs = store.data.first()
        val username = prefs[USERNAME] ?: return null
        val secret = prefs[SECRET] ?: return null
        val domain = prefs[DOMAIN] ?: return null
        return SoftphoneSettings(username, secret, domain)
    }

    suspend fun save(settings: SoftphoneSettings?) {
        store.edit { prefs ->
            if (settings == null) {
                prefs.clear()
            } else {
                prefs[USERNAME] = settings.username
                prefs[SECRET] = settings.secret
                prefs[DOMAIN] = settings.domain
            }
        }
    }

    companion object {
        private const val STORE_NAME = "companion_softphone"
        private val USERNAME = stringPreferencesKey("username")
        private val SECRET = stringPreferencesKey("secret")
        private val DOMAIN = stringPreferencesKey("domain")

        private fun storeFor(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { context.preferencesDataStoreFile(STORE_NAME) }
    }
}
