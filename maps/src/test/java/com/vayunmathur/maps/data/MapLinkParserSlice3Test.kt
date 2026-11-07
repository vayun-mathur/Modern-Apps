package com.vayunmathur.maps.data

import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): [MapLinkParser] — every external link
 * form that enters through [MainActivity], pinned directly (pure Kotlin, no
 * `android.net.Uri`).
 *
 * The activity tests cover the routing consequences; this covers the parse
 * table itself: geo points/searches/labels/zoom, `google.navigation` modes,
 * web place/search/dir links, and the malformed-link null contract.
 */
class MapLinkParserSlice3Test {

    @Test
    fun `bare geo point`() {
        val link = MapLinkParser.parse("geo:37.7749,-122.4194")
        assertEquals(37.7749, link?.lat)
        assertEquals(-122.4194, link?.lng)
        assertNull(link?.query)
        assertFalse(link!!.navigate)
        assertTrue(link.hasTarget)
    }

    @Test
    fun `geo search without a point`() {
        val link = MapLinkParser.parse("geo:0,0?q=Coffee")
        assertEquals("Coffee", link?.query)
        assertNull(link?.lat)
        assertNull(link?.lng)
    }

    @Test
    fun `geo named place near a point`() {
        val link = MapLinkParser.parse("geo:37.7749,-122.4194?q=Pier+39")
        assertEquals("Pier 39", link?.query)
        assertEquals(37.7749, link?.lat)
    }

    @Test
    fun `geo labelled point takes coordinates from q`() {
        val link = MapLinkParser.parse("geo:0,0?q=37.7749,-122.4194(Label)")
        assertEquals("Label", link?.query)
        assertEquals(37.7749, link?.lat)
        assertEquals(-122.4194, link?.lng)
    }

    @Test
    fun `geo bare coordinates in q without label`() {
        val link = MapLinkParser.parse("geo:0,0?q=37.7749,-122.4194")
        assertNull(link?.query)
        assertEquals(37.7749, link?.lat)
    }

    @Test
    fun `geo 0,0 without q has no target`() {
        assertNull(MapLinkParser.parse("geo:0,0"))
    }

    @Test
    fun `geo zoom is honoured in range and dropped out of range`() {
        assertEquals(17.0, MapLinkParser.parse("geo:37.7,-122.4?z=17")?.zoom)
        assertNull(MapLinkParser.parse("geo:37.7,-122.4?z=99")?.zoom)
        assertNull(MapLinkParser.parse("geo:37.7,-122.4?z=bogus")?.zoom)
    }

    @Test
    fun `navigation to coordinates with drive mode`() {
        val link = MapLinkParser.parse("google.navigation:q=37.7749,-122.4194&mode=d")
        assertTrue(link!!.navigate)
        assertEquals(RouteService.TravelMode.DRIVE, link.mode)
        assertEquals(37.7749, link.lat)
    }

    @Test
    fun `navigation to a search with walk mode`() {
        val link = MapLinkParser.parse("google.navigation:q=Pier+39&mode=w")
        assertTrue(link!!.navigate)
        assertEquals(RouteService.TravelMode.WALK, link.mode)
        assertEquals("Pier 39", link.query)
    }

    @Test
    fun `navigation two-wheeler maps to drive and bike to bicycle`() {
        assertEquals(
            RouteService.TravelMode.DRIVE,
            MapLinkParser.parse("google.navigation:q=1.0,2.0&mode=l")?.mode,
        )
        assertEquals(
            RouteService.TravelMode.BICYCLE,
            MapLinkParser.parse("google.navigation:q=1.0,2.0&mode=b")?.mode,
        )
    }

    @Test
    fun `navigation without q has no target and parses to null`() {
        // parseNavigation yields navigate=true with no destination, but
        // parse() drops target-less links: the caller must do nothing.
        assertNull(MapLinkParser.parse("google.navigation:mode=d"))
    }

    @Test
    fun `web place link with camera`() {
        val link = MapLinkParser.parse("https://www.google.com/maps/place/Pier+39/@37.7749,-122.4194,15z")
        assertEquals("Pier 39", link?.query)
        assertEquals(37.7749, link?.lat)
        assertEquals(15.0, link?.zoom)
    }

    @Test
    fun `web search link`() {
        val link = MapLinkParser.parse("https://www.google.com/maps/search/coffee+near+me")
        assertEquals("coffee near me", link?.query)
    }

    @Test
    fun `web search with q param and coordinate query becomes a point`() {
        val link = MapLinkParser.parse("https://maps.google.com/maps?q=37.7749,-122.4194")
        assertEquals(37.7749, link?.lat)
        assertNull(link?.query)
    }

    @Test
    fun `dir link with destination navigates`() {
        val link = MapLinkParser.parse(
            "https://www.google.com/maps/dir/?api=1&destination=Pier+39&travelmode=walking",
        )
        assertTrue(link!!.navigate)
        assertEquals("Pier 39", link.query)
        assertEquals(RouteService.TravelMode.WALK, link.mode)
    }

    @Test
    fun `dir link with coordinate destination navigates to the point`() {
        val link = MapLinkParser.parse(
            "https://www.google.com/maps/dir/?api=1&destination=37.7749,-122.4194&travelmode=transit",
        )
        assertEquals(37.7749, link?.lat)
        assertEquals(RouteService.TravelMode.TRANSIT, link?.mode)
    }

    @Test
    fun `dir path segment destination navigates`() {
        val link = MapLinkParser.parse("https://www.google.com/maps/dir/Home/Work")
        assertTrue(link!!.navigate)
        assertEquals("Work", link.query)
    }

    @Test
    fun `bare dir with camera navigates to the camera`() {
        val link = MapLinkParser.parse("https://www.google.com/maps/dir/@37.7749,-122.4194,15z")
        assertTrue(link!!.navigate)
        assertEquals(37.7749, link?.lat)
    }

    @Test
    fun `garbage links return null without throwing`() {
        assertNull(MapLinkParser.parse("https://example.com/"))
        assertNull(MapLinkParser.parse("not a link"))
        assertNull(MapLinkParser.parse(""))
        assertNull(MapLinkParser.parse("geo:"))
    }

    @Test
    fun `map link target requires a query or both coordinates`() {
        assertFalse(MapLink(query = "  ").hasTarget)
        assertTrue(MapLink(lat = 1.0, lng = null).hasTarget.not())
        assertTrue(MapLink(lat = 1.0, lng = 2.0).hasTarget)
    }
}
