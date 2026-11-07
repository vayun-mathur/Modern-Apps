package com.vayunmathur.maps.data

import com.vayunmathur.library.map.GeoPoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): [parse] resolves tapped basemap
 * features to admin labels, and [SpecificFeature] matching covers the
 * routable types.
 *
 * The Wikidata enrichment is skipped here by omitting the `wikidata`
 * property, so no network is touched: the label/region resolution is what
 * is pinned, including the unknown-kind null and the missing-name null.
 */
class SpecificFeatureSlice3Test {

    private fun feature(kind: String?, name: String?, extra: Map<String, JsonPrimitive> = emptyMap()): Feature1 {
        val props = mutableMapOf<String, JsonPrimitive>()
        if (kind != null) props["kind"] = JsonPrimitive(kind)
        if (name != null) props["name"] = JsonPrimitive(name)
        props.putAll(extra)
        return Feature(
            geometry = Point(GeoPoint(-122.4, 37.8)),
            properties = JsonObject(props),
        )
    }

    @Test
    fun `country label parses with its baked region link`() = runBlocking {
        val parsed = parse(feature("country", "France", mapOf("regionId" to JsonPrimitive(12345L))))
        assertTrue(parsed is SpecificFeature.Admin0Label)
        assertEquals("France", parsed.name)
        assertEquals(12345L, parsed.regionId)
        assertNull(parsed.iso)
        assertNull(parsed.wikipedia)
    }

    @Test
    fun `region and locality labels parse`() = runBlocking {
        val region = parse(feature("region", "Bavaria"))
        assertTrue(region is SpecificFeature.Admin1Label)
        assertEquals("Bavaria", (region as SpecificFeature.Admin1Label).name)
        val city = parse(feature("locality", "Munich"))
        assertTrue(city is SpecificFeature.Admin2Label)
    }

    @Test
    fun `english name wins over the local name`() = runBlocking {
        val parsed = parse(
            feature(
                "country",
                "Deutschland",
                mapOf("name:en" to JsonPrimitive("Germany")),
            ),
        )
        assertTrue(parsed is SpecificFeature.Admin0Label)
        assertEquals("Germany", parsed.name)
    }

    @Test
    fun `unknown kinds and missing names resolve to null`() = runBlocking {
        assertNull(parse(feature("venue", "Somewhere")))
        assertNull(parse(feature("country", null)))
        assertNull(parse(Feature(Point(GeoPoint(0.0, 0.0)), null)))
    }

    @Test
    fun `routable features match by name and position`() {
        val place = SpecificFeature.GenericPlace(
            name = "Cafe",
            phone = null,
            website = null,
            openingHours = null,
            position = GeoPoint(2.0, 1.0),
        )
        assertTrue(SavedPlace("Cafe", 1.0, 2.0).matches(place))
        assertTrue(!SavedPlace("Cafe", 9.0, 9.0).matches(place))
        val route = SpecificFeature.Route(waypoints = listOf(place, null))
        assertEquals(2, route.waypoints.size)
    }
}
