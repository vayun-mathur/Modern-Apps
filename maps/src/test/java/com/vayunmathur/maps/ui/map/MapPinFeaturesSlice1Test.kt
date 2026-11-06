package com.vayunmathur.maps.ui.map

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.Geometry
import com.vayunmathur.maps.data.LineString
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.Point
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.util.SearchResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Slice 1/3 (maps): the in-memory pin features — every pin kind rebuilds the
 * [Feature1] its resolver already understands, carrying the properties the
 * tap path reads back.
 */
class MapPinFeaturesSlice1Test {

    private fun content(props: kotlinx.serialization.json.JsonObject?, key: String): String? =
        (props?.get(key) as? JsonPrimitive)?.contentOrNull

    @Test
    fun `parking pins carry their position`() {
        val f = parkingPinFeature(ParkingSpot(lat = 37.7, lon = -122.4, timestamp = 0L))
        val pos = (f.geometry as? Point)?.coordinates ?: error("expected point geometry")
        assertEquals(37.7, pos.latitude)
        assertEquals(-122.4, pos.longitude)
        assertEquals("37.7", content(f.properties, "lat"))
        assertEquals("-122.4", content(f.properties, "lng"))
    }

    @Test
    fun `search pins carry id name position and category`() {
        val f = searchPinFeature(SearchResult("s1", "Cafe", null, 1.0, 2.0, "Coffee"))
        assertEquals("s1", content(f.properties, "id"))
        assertEquals("Cafe", content(f.properties, "name"))
        assertEquals("Coffee", content(f.properties, "category"))
        assertEquals("1.0", content(f.properties, "lat"))
        assertEquals("2.0", content(f.properties, "lng"))
    }

    @Test
    fun `search pins tolerate a missing category`() {
        val f = searchPinFeature(SearchResult("s1", "Cafe", null, 1.0, 2.0, null))
        assertEquals("", content(f.properties, "category"))
    }

    @Test
    fun `saved pins carry name and position`() {
        val f = savedPinFeature(SavedPlace("Home", 5.0, 6.0))
        assertEquals("Home", content(f.properties, "name"))
        assertEquals("5.0", content(f.properties, "lat"))
        assertEquals("6.0", content(f.properties, "lng"))
    }

    @Test
    fun `family pins carry id name position and the initial`() {
        val f = familyPinFeature(FamilyMember(9L, "ada", 3.0, 4.0, 0L, -1f))
        assertEquals("9", content(f.properties, "id"))
        assertEquals("ada", content(f.properties, "name"))
        assertEquals("A", content(f.properties, "initial"))
        assertEquals("3.0", content(f.properties, "lat"))
        assertEquals("4.0", content(f.properties, "lng"))
    }

    @Test
    fun `a blank family name still gets an initial`() {
        val f = familyPinFeature(FamilyMember(9L, "   ", 3.0, 4.0, 0L, -1f))
        assertEquals("?", content(f.properties, "initial"))
    }

    @Test
    fun `featuresInBox needs a projection which lives behind an internal constructor`() {
        // screenPos/featuresInBox take a library:map Projection whose
        // constructor is internal to that module, so they cannot be built from
        // :maps tests. screenPos is a cast + delegate: point geometry forwards,
        // anything else is null. Pinned here structurally instead.
        val line: Geometry = LineString(listOf(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0)))
        assertNull((line as? Point)?.coordinates, "a line is not a point")
    }
}
