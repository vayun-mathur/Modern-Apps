package com.vayunmathur.maps.data

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.ui.toSelectedFamilyMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Family pins become selectable places for the existing place-sheet path.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
class FamilyLocationLayerSlice2Test {

    private fun feature(
        name: String?,
        lat: Double?,
        lng: Double?,
    ): Feature1 = Feature<Geometry, JsonObject?>(
        Point(GeoPoint(0.0, 0.0)),
        buildJsonObject {
            if (name != null) put("name", name)
            if (lat != null) put("lat", lat)
            if (lng != null) put("lng", lng)
        },
    )

    @Test
    fun `a full pin becomes a place at its position`() {
        val place = feature("Ada", 37.8, -122.4).toSelectedFamilyMember()
            as SpecificFeature.GenericPlace
        assertEquals("Ada", place.name)
        assertEquals(37.8, place.position.latitude)
        assertEquals(-122.4, place.position.longitude)
    }

    @Test
    fun `a null properties object selects nothing`() {
        assertNull(
            Feature<Geometry, JsonObject?>(Point(GeoPoint(0.0, 0.0)), null)
                .toSelectedFamilyMember()
        )
    }

    @Test
    fun `a pin without a name selects nothing`() {
        assertNull(feature(null, 37.8, -122.4).toSelectedFamilyMember())
    }

    @Test
    fun `a pin with a blank name selects nothing`() {
        assertNull(feature("   ", 37.8, -122.4).toSelectedFamilyMember())
    }

    @Test
    fun `a pin without coordinates selects nothing`() {
        assertNull(feature("Ada", null, null).toSelectedFamilyMember())
    }

    @Test
    fun `a pin with only latitude selects nothing`() {
        assertNull(feature("Ada", 37.8, null).toSelectedFamilyMember())
    }

    @Test
    fun `a non-numeric coordinate selects nothing`() {
        val f = Feature<Geometry, JsonObject?>(
            Point(GeoPoint(0.0, 0.0)),
            buildJsonObject {
                put("name", "Ada")
                put("lat", JsonPrimitive("north"))
                put("lng", JsonPrimitive(-122.4))
            },
        )
        assertNull(f.toSelectedFamilyMember())
    }
}
