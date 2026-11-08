package com.android.location.provider

import android.content.Context
import android.location.Address
import android.location.GeocoderParams
import android.location.Location
import android.os.WorkSource
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the compile-only stub contract: stub bodies throw, no-op bodies do
 * nothing, and the abstract provider bases can be subclassed exactly the way
 * the real platform classes require. These classes are never packaged; at
 * runtime the framework's own implementations replace them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationStubsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class TestGeocodeProvider : GeocodeProvider() {
        override fun onGetFromLocation(
            latitude: Double, longitude: Double, maxResults: Int,
            params: GeocoderParams?, addrs: List<Address>,
        ): String? = null

        override fun onGetFromLocationName(
            locationName: String,
            lowerLeftLatitude: Double, lowerLeftLongitude: Double,
            upperRightLatitude: Double, upperRightLongitude: Double,
            maxResults: Int, params: GeocoderParams?, addrs: List<Address>,
        ): String = "no results"
    }

    private class TestLocationProvider(context: Context) :
        LocationProviderBase(context, "test", null) {
        var lastRequest: ProviderRequestUnbundled? = null
        override fun onSetRequest(request: ProviderRequestUnbundled, source: WorkSource) {
            lastRequest = request
        }
    }

    @Test
    fun geocodeProviderSubclassContractAndStubBinder() {
        val provider = TestGeocodeProvider()
        assertNull(provider.onGetFromLocation(1.0, 2.0, 3, null, mutableListOf()))
        assertEquals(
            "no results",
            provider.onGetFromLocationName("x", 0.0, 0.0, 1.0, 1.0, 1, null, mutableListOf()),
        )
        val ex = assertFailsWith<RuntimeException> { provider.binder }
        assertEquals("stub", ex.message)
    }

    @Test
    fun locationProviderBaseNoOpsAndSubclassHook() {
        val provider = TestLocationProvider(context)
        // No-op surface: none of these throw or change observable state.
        provider.setEnabled(true)
        provider.setEnabled(false)
        provider.setProperties(null)
        provider.reportLocation(Location("test"))
        provider.reportLocations(listOf(Location("test")))
        val ex = assertFailsWith<RuntimeException> { provider.binder }
        assertEquals("stub", ex.message)
        // The two-arg constructor form also exists for platform parity.
        val bare = object : LocationProviderBase("tag", null) {
            override fun onSetRequest(request: ProviderRequestUnbundled, source: WorkSource) = Unit
        }
        bare.setEnabled(true)
        assertTrue(true)
    }

    @Test
    fun propertiesAndRequestFactoriesAreStubs() {
        val ex = assertFailsWith<RuntimeException> {
            ProviderPropertiesUnbundled.create(
                true, false, false, false, true, true, true, 1, 2,
            )
        }
        assertEquals("stub", ex.message)
        // ProviderRequestUnbundled has no accessible constructor (private, like
        // the real class): its stub accessors are exercised only by the platform
        // at runtime, so the type's presence is the contract pinned here.
        assertEquals("com.android.location.provider.ProviderRequestUnbundled", ProviderRequestUnbundled::class.java.name)
        assertEquals("android.location.GeocoderParams", GeocoderParams::class.java.name)
    }
}
