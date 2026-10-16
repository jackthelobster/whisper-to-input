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
        var updates = 0
        var failUpdate: Int? = null
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            updates++
            if (updates == failUpdate) error("private settings failure")
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

    @Test fun repeatedLoadsUseCacheAndSavesReplaceIt() = runBlocking {
        val credentials = MemoryCredentials()
        val repository = SettingsRepository(MemoryStore(), credentials)
        val first = repository.load()
        credentials.failRead = true
        assertEquals(first, repository.load())
        val updated = first.copy(model = "updated-model", allowInsecure = true)
        repository.save(updated)
        assertEquals(updated, repository.load())
        assertEquals(1L, repository.changes.value)
    }

    @Test fun failedSaveInvalidatesPreviouslyCachedCredentials() = runBlocking {
        val credentials = MemoryCredentials()
        val repository = SettingsRepository(MemoryStore(), credentials)
        val first = repository.load()
        credentials.failWrite = true
        try { repository.save(first.copy(allowInsecure = true)); fail("Save must fail") } catch (_: TranscriptionException) { }
        try { repository.load(); fail("Cache must be invalidated") } catch (_: TranscriptionException) { }
        assertEquals(1L, repository.changes.value)
    }

    private suspend fun assertRecoveryRequired(store: MemoryStore, credentials: MemoryCredentials) {
        assertNull(store.state.value[API_KEY])
        assertEquals(true, store.state.value[CREDENTIAL_RECOVERY_REQUIRED])
        repeat(3) {
            try {
                // New repositories must honor durable state, not just an in-memory failure flag.
                SettingsRepository(store, credentials).load()
                fail("Expected explicit credential recovery")
            } catch (e: TranscriptionException) {
                assertTrue(e.message!!.contains("Re-enter"))
                assertFalse(e.message!!.contains("private"))
            }
        }
    }

    @Test fun defaultsMatchApprovedEndpointAndSafeUiFlags() = runBlocking {
        val settings = SettingsRepository(MemoryStore(), MemoryCredentials()).load()
        assertEquals(AppSettings.DEFAULT_ENDPOINT, settings.endpoint)
        assertEquals(AppSettings.DEFAULT_MODEL, settings.model)
        assertEquals("en", settings.language)
        assertEquals("", settings.apiKey)
        assertFalse(settings.allowInsecure)
        assertFalse(settings.autoRecord)
        assertFalse(settings.autoSwitch)
        assertFalse(settings.trailingSpace)
        assertFalse(settings.showShiftEnter)
    }

    @Test fun shiftEnterTogglePersistsAcrossRepositoryInstances() = runBlocking {
        val store = MemoryStore()
        val credentials = MemoryCredentials()
        val repository = SettingsRepository(store, credentials)
        repository.save(AppSettings(showShiftEnter = true, allowInsecure = true))
        assertTrue(SettingsRepository(store, credentials).load().showShiftEnter)
        repository.save(repository.load().copy(showShiftEnter = false))
        assertFalse(SettingsRepository(store, credentials).load().showShiftEnter)
    }

    @Test fun migrationEncryptsLegacyCredentialThenRemovesPlaintext() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "unit-test-token"))
        val credentials = MemoryCredentials()
        val repository = SettingsRepository(store, credentials)
        assertEquals("unit-test-token", repository.load().apiKey)
        assertNull(store.state.value[API_KEY])
        assertEquals("unit-test-token", credentials.value)
        assertNull(store.state.value[CREDENTIAL_RECOVERY_REQUIRED])
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
        assertRecoveryRequired(store, credentials)
        credentials.failWrite = false
        assertRecoveryRequired(store, credentials)
        assertEquals(0, credentials.writes)
    }

    @Test fun successfulExplicitSaveRecoversFailedMigration() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "unit-test-token"))
        val credentials = MemoryCredentials().apply { failWrite = true }
        try {
            SettingsRepository(store, credentials).load()
            fail("Expected migration failure")
        } catch (_: TranscriptionException) { }
        assertRecoveryRequired(store, credentials)
        credentials.failWrite = false
        val settings = AppSettings(endpoint = "https://example.test/transcribe", apiKey = "replacement-token")
        SettingsRepository(store, credentials).save(settings)
        assertNull(store.state.value[CREDENTIAL_RECOVERY_REQUIRED])
        assertEquals(settings, SettingsRepository(store, credentials).load())
    }

    @Test fun failingExplicitRecoveryKeepsLoadsBlocked() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "unit-test-token"))
        val credentials = MemoryCredentials().apply { failWrite = true }
        val repository = SettingsRepository(store, credentials)
        try {
            repository.load()
            fail("Expected migration failure")
        } catch (_: TranscriptionException) { }
        try {
            repository.save(AppSettings(allowInsecure = true))
            fail("Expected recovery save failure")
        } catch (e: TranscriptionException) {
            assertFalse(e.message!!.contains("private"))
        }
        assertRecoveryRequired(store, credentials)
        credentials.failWrite = false
        assertRecoveryRequired(store, credentials)
        repository.save(AppSettings(allowInsecure = true))
        assertNull(store.state.value[CREDENTIAL_RECOVERY_REQUIRED])
        assertEquals("", repository.load().apiKey)
    }

    @Test fun failingExplicitSaveRemovesPlaintextAndBlocksPreviousCredentials() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "stale-token"))
        val credentials = MemoryCredentials("previous-token").apply { failWrite = true }
        try {
            SettingsRepository(store, credentials).save(AppSettings(allowInsecure = true))
            fail("Expected credential save failure")
        } catch (_: TranscriptionException) { }
        assertEquals("previous-token", credentials.value)
        assertRecoveryRequired(store, credentials)
    }

    @Test fun failedSettingsCommitBlocksEvenWhenCredentialEndpointStillMatches() = runBlocking {
        val endpoint = "https://example.test/transcribe"
        val store = MemoryStore(preferencesOf(ENDPOINT to endpoint, API_KEY to "stale-token"))
            .apply { failUpdate = 2 }
        val credentials = MemoryCredentials("previous-token")
        val settings = AppSettings(endpoint = endpoint, apiKey = "", model = "replacement-model")
        try {
            SettingsRepository(store, credentials).save(settings)
            fail("Expected settings commit failure")
        } catch (e: TranscriptionException) {
            assertFalse(e.message!!.contains("private"))
        }
        assertNull(credentials.value)
        assertRecoveryRequired(store, credentials)
        SettingsRepository(store, credentials).save(settings)
        assertNull(store.state.value[CREDENTIAL_RECOVERY_REQUIRED])
        assertEquals(settings, SettingsRepository(store, credentials).load())
    }

    @Test fun failedMigrationCompletionCommitRequiresExplicitRecovery() = runBlocking {
        val store = MemoryStore(preferencesOf(API_KEY to "unit-test-token")).apply { failUpdate = 2 }
        val credentials = MemoryCredentials()
        try {
            SettingsRepository(store, credentials).load()
            fail("Expected migration completion failure")
        } catch (_: TranscriptionException) { }
        assertEquals("unit-test-token", credentials.value)
        assertRecoveryRequired(store, credentials)
        assertEquals(1, credentials.writes)
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
        repository.save(AppSettings(allowInsecure = true))
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
    @Test fun legacyFallbackInheritsMainModelAndLanguage() = runBlocking {
        val store = MemoryStore(preferencesOf(MODEL to "custom-model", LANGUAGE_CODE to "fr",
            FALLBACK_ENDPOINT to "https://fallback.example/v1/audio/transcriptions"))
        val settings = SettingsRepository(store, MemoryCredentials()).load()
        assertEquals("custom-model", settings.fallbackModel)
        assertEquals("fr", settings.fallbackLanguage)
    }

    @Test fun fallbackRoundTripsWithSeparateEncryptedCredential() = runBlocking {
        val store = MemoryStore()
        val main = MemoryCredentials()
        val fallback = MemoryCredentials()
        val repository = SettingsRepository(store, main, fallback)
        val settings = AppSettings(endpoint = "https://main.example/v1/audio/transcriptions",
            apiKey = "main-token", fallbackEndpoint = "https://fallback.example/v1/audio/transcriptions",
            fallbackApiKey = "fallback-token", fallbackModel = "whisper-1", fallbackLanguage = "fr")
        repository.save(settings)
        assertEquals(settings, repository.load())
        assertEquals(settings.endpoint, main.endpoint)
        assertEquals(settings.fallbackEndpoint, fallback.endpoint)
        assertFalse(store.state.value.asMap().values.contains("fallback-token"))
        repository.save(settings.copy(fallbackEndpoint = "", fallbackApiKey = ""))
        assertNull(fallback.value)
        assertEquals("", repository.load().fallbackEndpoint)
    }

    @Test fun tornFallbackSaveAndCredentialFailureRequireRecovery() = runBlocking {
        val store = MemoryStore(preferencesOf(FALLBACK_ENDPOINT to "https://old.example/v1/audio/transcriptions"))
        val main = MemoryCredentials()
        val fallback = MemoryCredentials("fallback-token").apply {
            endpoint = "https://new.example/v1/audio/transcriptions"
        }
        try {
            SettingsRepository(store, main, fallback).load()
            fail("Mismatched fallback credentials must fail closed")
        } catch (_: TranscriptionException) { }
        fallback.failWrite = true
        try {
            SettingsRepository(store, main, fallback).save(AppSettings(allowInsecure = true))
            fail("Failed credential persistence must require recovery")
        } catch (_: TranscriptionException) { }
        assertRecoveryRequired(store, main)
    }

}
