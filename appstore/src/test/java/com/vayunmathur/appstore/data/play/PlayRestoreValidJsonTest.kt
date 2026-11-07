package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.providers.DeviceInfoProvider as GplayDeviceInfoProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Restore with a well-formed persisted session: the document decodes,
 * but the validity probe cannot reach Play offline, so the session
 * is invalidated (state back to Idle, persisted keys removed) rather
 * than trusted blindly.
 */
@RunWith(RobolectricTestRunner::class)
class PlayRestoreValidJsonTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `a decodable but unverifiable session is invalidated at restore`() = runBlocking {
        val authData = AuthData(
            email = "anon@example.org",
            aasToken = "aas-token",
            authToken = "auth-token",
            isAnonymous = true,
            deviceInfoProvider = GplayDeviceInfoProvider(
                DeviceInfoProvider.auroraProfile(context),
                "en_US",
            ),
        )
        val encoded = json.encodeToString(AuthData.serializer(), authData)

        val file = File(context.filesDir, "datastore/play_auth.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            store.edit { prefs ->
                prefs[stringPreferencesKey("play_auth_json")] = encoded
                prefs[longPreferencesKey("play_auth_dispensed_at")] =
                    System.currentTimeMillis()
            }
        } finally {
            scope.cancel()
        }

        val repo = PlayRepository(context)
        repo.restore()
        assertEquals(PlayAuthState.Idle, repo.authState.value)

        // Invalidation removes the persisted session, so the next cold
        // start does not probe the same dead account again. Read the
        // proto file back directly: a second DataStore on the same
        // file would conflict with the still-active production one.
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(!bytes.contains("anon@example.org"), "session still persisted")
    }
}
