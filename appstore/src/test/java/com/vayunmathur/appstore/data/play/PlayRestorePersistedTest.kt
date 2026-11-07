package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Play session restore against persisted-but-unusable auth: a corrupt
 * stored document and a stale one both leave the repository Idle
 * (a fresh dispense happens lazily on first use, not at restore).
 * The persisted file is written through a throwaway DataStore on the
 * same path the production delegate uses, then released.
 */
@RunWith(RobolectricTestRunner::class)
class PlayRestorePersistedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun seedPersisted(json: String?, dispensedAt: Long?) = runBlocking {
        val file = File(context.filesDir, "datastore/play_auth.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            store.edit { prefs ->
                if (json != null) prefs[stringPreferencesKey("play_auth_json")] = json
                if (dispensedAt != null) {
                    prefs[longPreferencesKey("play_auth_dispensed_at")] = dispensedAt
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a corrupt stored auth document restores to idle`() = runBlocking {
        seedPersisted(json = "this is not auth json", dispensedAt = System.currentTimeMillis())
        val repo = PlayRepository(context)
        repo.restore()
        assertEquals(PlayAuthState.Idle, repo.authState.value)
    }

    @Test
    fun `a stale stored auth document is invalidated at restore`() = runBlocking {
        seedPersisted(json = "{}", dispensedAt = 1L)
        val repo = PlayRepository(context)
        repo.restore()
        assertEquals(PlayAuthState.Idle, repo.authState.value)
    }

    @Test
    fun `a blank stored auth document restores to idle`() = runBlocking {
        seedPersisted(json = "", dispensedAt = System.currentTimeMillis())
        val repo = PlayRepository(context)
        repo.restore()
        assertEquals(PlayAuthState.Idle, repo.authState.value)
    }
}
