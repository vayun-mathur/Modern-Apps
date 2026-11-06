package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * The last-resort GTFS route-colour lookup: with no feed assets shipped,
 * an absent feed reads as null rather than failing.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class GTFSProviderAssetsSlice2Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `an absent feed returns null`() {
        assertNull(GTFSProvider.getRouteColor(context, "no-such-feed", "N"))
    }

    @Test
    fun `a repeated lookup for an absent feed still returns null`() {
        assertNull(GTFSProvider.getRouteColor(context, "no-such-feed", "N"))
        assertNull(GTFSProvider.getRouteColor(context, "no-such-feed", "38"))
    }
}
