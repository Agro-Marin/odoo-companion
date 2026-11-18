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
import com.odoocompanion.config.DeviceConfig
import com.odoocompanion.config.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SoftphoneStore(private val store: DataStore<Preferences>, config: DeviceConfig) {
    constructor(context: Context, config: DeviceConfig) : this(storeFor(context), config)

    private val active = combine(store.data, config.settings) { prefs, settings ->
        prefs.credentialsFor(settings)
    }.distinctUntilChanged()

    val configured: Flow<Boolean> = active.map { it != null }.distinctUntilChanged()

    val domain: Flow<String?> = active.map { it?.domain }.distinctUntilChanged()

    suspend fun current(): SoftphoneSettings? = active.first()

    suspend fun current(owner: Settings): SoftphoneSettings? =
        store.data.first().credentialsFor(owner)

    private fun Preferences.credentialsFor(owner: Settings): SoftphoneSettings? {
        if (!owner.isEnrolled || this[ENROLLMENT] != owner.enrollment) return null
        val username = this[USERNAME] ?: return null
        val secret = this[SECRET] ?: return null
        val domain = this[DOMAIN] ?: return null
        return SoftphoneSettings(username, secret, domain)
    }

    suspend fun save(owner: Settings, credentials: SoftphoneSettings) {
        store.edit { prefs ->
            prefs[ENROLLMENT] = requireNotNull(owner.enrollment)
            prefs[USERNAME] = credentials.username
            prefs[SECRET] = credentials.secret
            prefs[DOMAIN] = credentials.domain
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        private const val STORE_NAME = "companion_softphone"
        private val ENROLLMENT = stringPreferencesKey("enrollment")
        private val USERNAME = stringPreferencesKey("username")
        private val SECRET = stringPreferencesKey("secret")
        private val DOMAIN = stringPreferencesKey("domain")

        private fun storeFor(context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            ) { context.preferencesDataStoreFile(STORE_NAME) }
    }
}
