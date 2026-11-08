package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.data.providers.DeviceInfoProvider as GplayDeviceInfoProvider
import com.aurora.gplayapi.exceptions.GooglePlayException
import com.aurora.gplayapi.network.IHttpClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PlayStoreApi against a fake transport, so the failure contract is
 * exercised without any network: reads fail soft (null/empty) on both
 * a Play refusal and a transport failure, while search and purchase —
 * the two callers that must surface a refusal — propagate it.
 */
@RunWith(RobolectricTestRunner::class)
class PlayStoreApiFakeClientTest {

    private class FakeClient(private val failure: Exception?) : IHttpClient {
        override val responseCode: StateFlow<Int> = MutableStateFlow(0)

        private fun respond(): PlayResponse {
            failure?.let { throw it }
            return PlayResponse(
                isSuccessful = false,
                code = 429,
                errorString = "Too Many Requests",
                errorBytes = ByteArray(0),
                responseBytes = ByteArray(0),
            )
        }

        override fun get(url: String, headers: Map<String, String>): PlayResponse = respond()
        override fun get(
            url: String,
            headers: Map<String, String>,
            params: Map<String, String>,
        ): PlayResponse = respond()

        override fun get(
            url: String,
            headers: Map<String, String>,
            paramString: String,
        ): PlayResponse = respond()

        override fun getAuth(url: String): PlayResponse = respond()
        override fun post(
            url: String,
            headers: Map<String, String>,
            params: Map<String, String>,
        ): PlayResponse = respond()

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): PlayResponse = respond()

        override fun postAuth(url: String, body: ByteArray): PlayResponse = respond()
    }

    private fun api(client: IHttpClient): PlayStoreApi {
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
            client,
        )
    }

    @Test
    fun `reads fail soft when play refuses`() = runBlocking {
        val api = api(FakeClient(failure = null))
        assertNull(api.getDetails("com.example.missing"))
        assertTrue(api.getDetails(listOf("com.example.a")).isEmpty())
        assertTrue(api.topChart().isEmpty())
        assertTrue(api.homeClusters().isEmpty())
    }

    @Test
    fun `reads fail soft when the transport throws`() = runBlocking {
        val api = api(FakeClient(failure = IOException("offline")))
        assertNull(api.getDetails("com.example.missing"))
        assertTrue(api.getDetails(listOf("com.example.a")).isEmpty())
        assertTrue(api.topChart().isEmpty())
        assertTrue(api.homeClusters().isEmpty())
        assertEquals(emptyList(), api.search("maps", maxPages = 2) { false })
    }

    @Test
    fun `search propagates a play refusal so the caller can cycle accounts`() = runBlocking<Unit> {
        val api = api(FakeClient(failure = null))
        assertFailsWith<GooglePlayException> {
            api.search("maps", maxPages = 2) { false }
        }
    }

    @Test
    fun `purchase propagates failure instead of returning files`() = runBlocking<Unit> {
        val refused = api(FakeClient(failure = null))
        assertFailsWith<Exception> {
            refused.purchase("com.example.app", versionCode = 42L)
        }
        val offline = api(FakeClient(failure = IOException("offline")))
        assertFailsWith<Exception> {
            offline.purchase("com.example.app", versionCode = 42L)
        }
    }
}
