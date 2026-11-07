package com.vayunmathur.passwords.sync

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.PasswordRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunKdbxSyncExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun repositoryOverloadNotConfiguredWithoutUri() = runBlocking {
        KdbxSyncSettings.setEnabled(ctx, true)
        KdbxSyncSettings.setDocumentUri(ctx, "")
        val repo = PasswordRepository.get(ctx)
        assertEquals(KdbxSyncResult.NotConfigured, runKdbxSync(ctx, repo))
    }

    @Test fun repositoryOverloadNotConfiguredWithoutVaultKey() = runBlocking {
        KdbxSyncSettings.setEnabled(ctx, true)
        KdbxSyncSettings.setDocumentUri(ctx, "content://vault.kdbx")
        // No KDBX vault key has been stored in this sandbox.
        val repo = PasswordRepository.get(ctx)
        assertEquals(KdbxSyncResult.NotConfigured, runKdbxSync(ctx, repo))
    }

    @Test fun safDocumentReadMissingThrowsFileNotFound() {
        val doc = SafKdbxDocument(ctx, Uri.parse("content://missing/vault.kdbx"))
        assertFailsWith<UnsupportedOperationException> { doc.read() }
    }

    @Test fun routeSerializationRoundTrips() {
        val json = Json
        assertEquals(
            Route.PasswordPage(42),
            json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.PasswordPage(42))),
        )
        assertEquals(Route.Menu, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Menu)))
        assertTrue(Route.PasswordEditPage(1) != Route.PasswordPage(1))
    }
}
