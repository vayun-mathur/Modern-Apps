package com.vayunmathur.networklocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeBridgesTest {

    @Test
    fun `geocoder field layout constants`() {
        assertEquals(10, GeocoderNative.FIELDS_PER_RESULT)
        assertEquals(0, GeocoderNative.F_LAT)
        assertEquals(1, GeocoderNative.F_LON)
        assertEquals(2, GeocoderNative.F_NAME)
        assertEquals(3, GeocoderNative.F_HOUSE)
        assertEquals(4, GeocoderNative.F_STREET)
        assertEquals(5, GeocoderNative.F_CITY)
        assertEquals(6, GeocoderNative.F_STATE)
        assertEquals(7, GeocoderNative.F_COUNTRY)
        assertEquals(8, GeocoderNative.F_POSTCODE)
        assertEquals(9, GeocoderNative.F_KIND)
        assertEquals(1, GeocoderNative.KIND_ADDRESS)
        assertEquals(2, GeocoderNative.KIND_STREET)
        assertEquals(3, GeocoderNative.KIND_POI)
        assertEquals(4, GeocoderNative.KIND_PLACE)
    }

    @Test
    fun `native libraries load on the host`() {
        assertTrue(WpsStoreNative.available)
        assertTrue(GeocoderNative.available)
        assertTrue(NetworkLocationNative.available)
    }

    @Test
    fun `closing a zero handle is a no-op for both stores`() {
        WpsStoreNative.close(0L)
        GeocoderNative.close(0L)
    }

    @Test
    fun `opening a bad descriptor yields a zero handle`() {
        assertEquals(0L, WpsStoreNative.open(-1, 0L, 0L))
        assertEquals(0L, GeocoderNative.open(-1, 0L, 0L))
    }

    @Test
    fun `a single beacon positions at that beacon`() {
        val pos = NetworkLocationNative.estimatePosition(doubleArrayOf(37.4215, -122.084, 25.0))
        assertNotNull(pos)
        assertEquals(3, pos.size)
        assertEquals(37.4215, pos[0], 1e-4)
        assertEquals(-122.084, pos[1], 1e-4)
        assertTrue(pos[2] > 0.0, "accuracy must be positive, was ${pos[2]}")
    }

    @Test
    fun `agreeing beacons position between them`() {
        val pos = NetworkLocationNative.estimatePosition(
            doubleArrayOf(
                37.4215, -122.084, 20.0,
                37.4216, -122.0841, 20.0,
                37.4214, -122.0839, 20.0,
            ),
        )
        assertNotNull(pos)
        assertEquals(37.4215, pos[0], 5e-4)
        assertEquals(-122.084, pos[1], 5e-4)
    }

    @Test
    fun `no usable beacons yields null`() {
        assertNull(NetworkLocationNative.estimatePosition(doubleArrayOf()))
        assertNull(NetworkLocationNative.estimatePosition(doubleArrayOf(37.0)))
        assertNull(NetworkLocationNative.estimatePosition(doubleArrayOf(37.0, -122.0)))
        assertNull(
            NetworkLocationNative.estimatePosition(
                doubleArrayOf(Double.NaN, Double.NaN, Double.NaN),
            ),
        )
    }

    @Test
    fun `non-finite beacons are ignored rather than poisoning the fix`() {
        val pos = NetworkLocationNative.estimatePosition(
            doubleArrayOf(
                Double.NaN, Double.NaN, Double.NaN,
                37.4215, -122.084, 25.0,
            ),
        )
        assertNotNull(pos)
        assertEquals(37.4215, pos[0], 1e-4)
        assertEquals(-122.084, pos[1], 1e-4)
    }
}
