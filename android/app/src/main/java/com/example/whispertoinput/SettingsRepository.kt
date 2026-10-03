package com.example.whispertoinput

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// The sole DataStore delegate: services and UI must not create another settings store.
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val SPEECH_TO_TEXT_BACKEND = stringPreferencesKey("speech-to-text-backend")
val ENDPOINT = stringPreferencesKey("endpoint")
val LANGUAGE_CODE = stringPreferencesKey("language-code")
val API_KEY = stringPreferencesKey("api-key") // Legacy migration only. Never write plaintext here.
val MODEL = stringPreferencesKey("model")
val AUTO_RECORDING_START = booleanPreferencesKey("is-auto-recording-start")
val AUTO_SWITCH_BACK = booleanPreferencesKey("auto-switch-back")
val ADD_TRAILING_SPACE = booleanPreferencesKey("add-trailing-space")
val POSTPROCESSING = stringPreferencesKey("postprocessing")
val ALLOW_INSECURE = booleanPreferencesKey("allow-insecure-gx10")

class SettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    private val credentials: CredentialStore
) {
    constructor(context: Context) : this(
        context.applicationContext.dataStore,
        KeystoreCredentialStore(context.applicationContext)
    )

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val preferences = store.data.first()
                migrateLegacyKey(preferences)
                val endpoint = preferences[ENDPOINT]?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT
                // The ciphertext and its destination commit together in one preferences file.
                // A crash between that commit and DataStore must fail closed, never send a
                // newly entered server's key to a previously configured endpoint.
                val boundEndpoint = credentials.boundEndpoint()
                if (boundEndpoint != null && boundEndpoint != endpoint) {
                    throw TranscriptionException("Saved credentials do not match the endpoint. Re-enter and save your settings.")
                }
                AppSettings(
                    endpoint = endpoint,
                    model = preferences[MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
                    language = preferences[LANGUAGE_CODE] ?: "en",
                    apiKey = credentials.read(),
                    autoRecord = preferences[AUTO_RECORDING_START] ?: false,
                    autoSwitch = preferences[AUTO_SWITCH_BACK] ?: false,
                    trailingSpace = preferences[ADD_TRAILING_SPACE] ?: false,
                    allowInsecure = preferences[ALLOW_INSECURE] ?: true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Do not silently fall back to an unauthenticated request after a restore/key failure.
                throw TranscriptionException("Settings or the saved API key could not be read. Re-enter and save your settings.")
            }
        }
    }

    suspend fun save(settings: AppSettings): Unit = withContext(Dispatchers.IO) {
        TranscriptionProtocol.validateSettings(settings)
        mutex.withLock {
            try {
                // Once persisting starts, finish both stores even if the UI is destroyed.
                withContext(NonCancellable) {
                    try {
                        credentials.writeBound(settings.apiKey, settings.endpoint)
                    } finally {
                        // Including empty legacy keys and encryption failure: never keep plaintext.
                        store.edit { it.remove(API_KEY) }
                    }
                    store.edit {
                        it[ENDPOINT] = settings.endpoint
                        it[MODEL] = settings.model
                        it[LANGUAGE_CODE] = settings.language
                        it[AUTO_RECORDING_START] = settings.autoRecord
                        it[AUTO_SWITCH_BACK] = settings.autoSwitch
                        it[ADD_TRAILING_SPACE] = settings.trailingSpace
                        it[ALLOW_INSECURE] = settings.allowInsecure
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw TranscriptionException("Settings could not be saved securely. Please try again.")
            }
        }
    }

    private suspend fun migrateLegacyKey(preferences: Preferences) {
        val legacy = preferences[API_KEY] ?: return
        withContext(NonCancellable) {
            try {
                // Existing encrypted credentials win; an old plaintext value must not overwrite them.
                if (!credentials.contains()) credentials.writeBound(legacy,
                    preferences[ENDPOINT]?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT)
            } finally {
                store.edit { it.remove(API_KEY) }
            }
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = AppSettings.DEFAULT_ENDPOINT
        const val DEFAULT_MODEL = AppSettings.DEFAULT_MODEL
        // Serializes migration/save across independently constructed UI and service repositories.
        private val mutex = Mutex()
    }
}
