package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.platform.SearchResult

import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.intents.FamilyMember
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Marker labels: every named pin carries its display name into the renderer, where it draws
 * beside the icon like a POI label. The parking pin has no name and stays icon-only.
 *
 * The tap path is unchanged (id-buffer pick into [MapHit]), so this pins only the label
 * mapping — a pin whose name went missing would draw a bare icon with no failing lookup.
 */
class BuildMarkersTest {

    private fun search(title: String) = SearchResult(
        id = "s1", title = title, subtitle = null, lat = 1.0, lon = 2.0, category = null,
    )

    private fun member(name: String) = FamilyMember(
        id = 9L, name = name, lat = 3.0, lng = 4.0, timestamp = 0L, battery = -1f,
    )

    @Test
    fun `family search and saved pins carry their names as labels`() {
        val (markers, _) = buildMarkers(
            searchResults = listOf(search("Cafe")),
            savedPlaces = listOf(SavedPlace("Home", 5.0, 6.0)),
            parkingSpot = null,
            familyMembers = listOf(member("Ada")),
        )

        assertEquals(
            listOf("Cafe", "Home", "Ada"),
            markers.map { it.label },
        )
    }

    @Test
    fun `marker hits still resolve with labels present`() {
        val (_, hits) = buildMarkers(
            searchResults = listOf(search("Cafe")),
            savedPlaces = emptyList(),
            parkingSpot = null,
            familyMembers = listOf(member("Ada")),
        )

        // One search pin (100_000 range) + one family pin (300_000 range).
        assertEquals(2, hits.size)
        assertTrue(hits.containsKey(100_000L))
        assertTrue(hits.containsKey(300_000L))
    }
}
