package com.vayunmathur.maps.data

import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The GeoJSON slice the app uses, and the longitude-first coordinate encoding.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 */
class GeoJsonSlice2Test {

    private val json = Json

    @Test
    fun `a point holds its coordinates`() {
        val p = Point(GeoPoint(longitude = -122.4, latitude = 37.8))
        assertEquals(-122.4, p.coordinates.longitude)
        assertEquals(37.8, p.coordinates.latitude)
    }

    @Test
    fun `a linestring holds its points in order`() {
        val line = LineString(
            listOf(
                GeoPoint(-122.4, 37.8),
                GeoPoint(-122.5, 37.9),
            )
        )
        assertEquals(2, line.coordinates.size)
        assertEquals(GeoPoint(-122.5, 37.9), line.coordinates[1])
    }

    @Test
    fun `a feature pairs geometry with properties`() {
        val f = Feature(Point(GeoPoint(-122.4, 37.8)), "engine-house")
        assertEquals("engine-house", f.properties)
        assertEquals(GeoPoint(-122.4, 37.8), f.geometry.coordinates)
    }

    @Test
    fun `coordinates encode longitude first`() {
        val encoded = json.encodeToString(
            GeoPointAsCoordinates,
            GeoPoint(longitude = -122.4, latitude = 37.8),
        )
        // Longitude first — the reverse of the usual lat/lng convention.
        assertEquals("[-122.4,37.8]", encoded)
    }

    @Test
    fun `coordinates decode longitude first`() {
        val decoded = json.decodeFromString<List<Double>>("""[-122.4, 37.8]""")
            .let { GeoPoint(longitude = it[0], latitude = it[1]) }
        assertEquals(GeoPoint(longitude = -122.4, latitude = 37.8), decoded)
    }

    @Test
    fun `coordinates round-trip through the serializer`() {
        val point = GeoPoint(longitude = 13.4, latitude = 52.5)
        val encoded = json.encodeToString(GeoPointAsCoordinates, point)
        assertEquals(point, json.decodeFromString(GeoPointAsCoordinates, encoded))
    }

    @Test
    fun `the serializer reuses the double-list descriptor`() {
        assertEquals(
            ListSerializer(Double.serializer()).descriptor,
            GeoPointAsCoordinates.descriptor,
        )
    }
}
