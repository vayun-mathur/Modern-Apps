package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.providers.DeviceInfoProvider as GplayDeviceInfoProvider
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayRepository.withApi session handling: without a cached session the
 * block never runs; with one, its result is returned; when the block
 * fails with an I/O error the dead session is invalidated and, since no
 * new account can be dispensed offline, the call answers null and the
 * auth state records the dispenser failure.
 */
@RunWith(RobolectricTestRunner::class)
class PlayWithApiTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun authData() = AuthData(
        email = "anon@example.org",
        aasToken = "aas-token",
        authToken = "auth-token",
        isAnonymous = true,
        deviceInfoProvider = GplayDeviceInfoProvider(
            DeviceInfoProvider.auroraProfile(context),
            "en_US",
        ),
    )

    private fun repoWithSession(): PlayRepository {
        val repo = PlayRepository(context)
        repo.cachedAuthData = authData()
        repo.dispensedAt = System.currentTimeMillis()
        return repo
    }

    @Test
    fun `withApi without a session returns null and never runs the block`() = runBlocking {
        val repo = PlayRepository(context)
        var ran = false
        val result = repo.withApi { ran = true; "value" }
        assertNull(result)
        assertTrue(!ran)
    }

    @Test
    fun `withApi returns the block result for a cached session`() = runBlocking {
        val repo = repoWithSession()
        val result = repo.withApi { "details-result" }
        assertEquals("details-result", result)
        // The session survives a successful call.
        assertEquals("anon@example.org", repo.cachedAuthData?.email)
    }

    @Test
    fun `withApi cycles a dead session and answers null when dispensing fails`() = runBlocking {
        val repo = repoWithSession()
        val result = repo.withApi<String> { throw IOException("connection reset") }
        assertNull(result)
        assertNull(repo.cachedAuthData, "dead session must be invalidated")
        assertTrue(
            repo.authState.value is PlayAuthState.Error,
            "auth state was ${repo.authState.value}",
        )
    }

    @Test
    fun `withFreshApi answers null when no session can be dispensed`() = runBlocking {
        val repo = PlayRepository(context)
        var ran = false
        val result = repo.withFreshApi { ran = true; "value" }
        assertNull(result)
        assertTrue(!ran)
    }

    @Test
    fun `detailsOrNull of no packages is an answered empty lookup`() = runBlocking {
        val repo = PlayRepository(context)
        assertEquals(emptyList(), repo.detailsOrNull(emptyList()))
    }
}
