package com.example.whispertoinput

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }

    private class MemoryCredentials(var value: String? = null) : CredentialStore {
        var writes = 0
        var failWrite = false
        var failRead = false
        var endpoint: String? = null
        override fun boundEndpoint(): String? = if (value != null) endpoint else null
        override fun writeBound(value: String, endpoint: String) {
            write(value)
            this.endpoint = endpoint
        }
        override fun contains() = value != null
        override fun read(): String {
            if (failRead) error("private credential failure")
            return value ?: ""
        }
        override fun write(value: String) {
            if (failWrite) error("private credential failure")
            this.value = value.takeIf { it.isNotEmpty() }
            writes++
        }
    }

    @Test fun defaultsMatchApprovedEndpointAndSafeUiFlags() = runBlocking {
        val settings = SettingsRepository(MemoryStore(), MemoryCredentials()).load()
        assertEquals(AppSettings.DEFAULT_ENDPOINT, settings.endpoint)
        assertEquals(AppSettings.DEFAULT_MODEL, settings.model)
        assertEquals("en", settings.language)
        assertEquals("", settings.apiKey)
        assertTrue(settings.allowInsecure)
        assertFalse(settings.autoRecord)
        assertFalse(settings.autoSwitch)
        assertFalse(settings.trailingSpace)
    }

    @Test fun migrationEncryptsLegacyCredentialThenRemovesPlaintext() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "unit-test-token"))
        val credentials = MemoryCredentials()
        val repository = SettingsRepository(store, credentials)
        assertEquals("unit-test-token", repository.load().apiKey)
        assertNull(store.state.value[API_KEY])
        assertEquals("unit-test-token", credentials.value)
        repository.load()
        assertEquals(1, credentials.writes)
    }

    @Test fun emptyLegacyKeyIsRemovedAndExistingCiphertextWins() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to ""))
        val credentials = MemoryCredentials("encrypted-existing-token")
        assertEquals("encrypted-existing-token", SettingsRepository(store, credentials).load().apiKey)
        assertNull(store.state.value[API_KEY])
        assertEquals(0, credentials.writes)
    }

    @Test fun emptyLegacyKeyWithNoEncryptedCredentialIsStillRemoved() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to ""))
        assertEquals("", SettingsRepository(store, MemoryCredentials()).load().apiKey)
        assertNull(store.state.value[API_KEY])
    }

    @Test fun encryptionFailureDoesNotLeavePlaintextOrExposeRawError() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "unit-test-token"))
        val credentials = MemoryCredentials().apply { failWrite = true }
        try {
            SettingsRepository(store, credentials).load()
            fail("Expected secure-storage failure")
        } catch (e: TranscriptionException) {
            assertFalse(e.message!!.contains("private credential failure"))
            assertFalse(e.message!!.contains("unit-test-token"))
        }
        assertNull(store.state.value[API_KEY])
    }

    @Test fun credentialRestoreFailureDoesNotSilentlyReturnAnEmptyKey() = runBlocking {
        val credentials = MemoryCredentials("unreadable-encrypted-token").apply { failRead = true }
        try {
            SettingsRepository(MemoryStore(), credentials).load()
            fail("Expected credential restore failure")
        } catch (e: TranscriptionException) {
            assertTrue(e.message!!.contains("Re-enter"))
            assertFalse(e.message!!.contains("private credential failure"))
        }
    }

    @Test fun savingPersistsNormalSettingsAndNeverStoresPlaintextKey() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "stale-token"))
        val credentials = MemoryCredentials()
        val repository = SettingsRepository(store, credentials)
        val settings = AppSettings(endpoint = "https://example.test/transcribe", model = "custom-model",
            language = "fr", apiKey = "unit-test-token", autoRecord = true, autoSwitch = true,
            trailingSpace = true, allowInsecure = false)
        repository.save(settings)
        assertEquals(settings, repository.load())
        assertNull(store.state.value[API_KEY])
        assertFalse(store.state.value.asMap().values.contains("unit-test-token"))
        repository.save(settings.copy(apiKey = ""))
        assertEquals("", repository.load().apiKey)
    }

    @Test fun tornSaveRefusesToUseNewKeyWithPreviousEndpoint() = runBlocking {
        val store = MemoryStore(preferencesOf(ENDPOINT to "https://previous.example/v1/audio/transcriptions"))
        val credentials = MemoryCredentials("new-server-key").apply {
            endpoint = "https://new.example/v1/audio/transcriptions"
        }
        try {
            SettingsRepository(store, credentials).load()
            fail("A mismatched destination must fail closed")
        } catch (e: TranscriptionException) {
            assertTrue(e.message!!.contains("Re-enter"))
            assertFalse(e.message!!.contains("new-server-key"))
        }
    }

    @Test fun explicitRecoveryCanClearAnUnreadableCredential() = runBlocking {
        val credentials = MemoryCredentials("damaged-key").apply { failRead = true }
        val repository = SettingsRepository(MemoryStore(), credentials)
        repository.save(AppSettings())
        credentials.failRead = false
        assertEquals("", repository.load().apiKey)
    }

    @Test fun insecureCredentialSaveIsRejectedBeforePersistence() = runBlocking {
        val credentials = MemoryCredentials()
        try {
            SettingsRepository(MemoryStore(), credentials).save(AppSettings(apiKey = "unit-test-token"))
            fail("Expected cleartext rejection")
        } catch (_: TranscriptionException) { }
        assertEquals(0, credentials.writes)
    }
}
