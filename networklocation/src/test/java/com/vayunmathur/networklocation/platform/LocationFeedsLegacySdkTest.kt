package com.vayunmathur.networklocation.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pre-Tiramisu branch of [reverseGeocode] uses the deprecated synchronous Geocoder API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationFeedsLegacySdkTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `reverse geocode on sdk 33 succeeds with null when nothing matches`() = runBlocking {
        val result = reverseGeocode(context, 37.4215, -122.084)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }
}
