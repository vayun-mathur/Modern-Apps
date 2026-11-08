package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.providers.DeviceInfoProvider as GplayDeviceInfoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayStoreApi with a syntactically valid session but no reachable
 * Play backend: every read must fail soft (null/empty) rather than
 * propagate a transport exception to the UI. Robolectric for the
 * Android Base64 the auth stack touches.
 */
@RunWith(RobolectricTestRunner::class)
class PlayStoreApiFailureTest {

    private fun api(): PlayStoreApi {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PlayStoreApi(
            AuthData(
                email = "anon@example.org",
                aasToken = "aas-token",
                authToken = "auth-token",
                isAnonymous = true,
                deviceInfoProvider = GplayDeviceInfoProvider(
                    DeviceInfoProvider.auroraProfile(context),
                    "en_US",
                ),
            ),
            PlayHttpClient(),
        )
    }

    @Test
    fun `details for one package is null when play cannot answer`() = runBlocking {
        assertNull(api().getDetails("com.example.missing"))
    }

    @Test
    fun `details for many packages is empty and an empty batch needs no call`() = runBlocking {
        assertTrue(api().getDetails(listOf("com.example.a", "com.example.b")).isEmpty())
        assertEquals(emptyList(), api().getDetails(emptyList()))
    }

    @Test
    fun `top chart and home clusters are empty when play cannot answer`() = runBlocking {
        assertTrue(api().topChart().isEmpty())
        assertTrue(api().homeClusters().isEmpty())
    }
}
