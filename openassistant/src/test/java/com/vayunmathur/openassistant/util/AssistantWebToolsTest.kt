package com.vayunmathur.openassistant.util

import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.maps.DirectionsLatLng
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.maps.DirectionsSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P2 pure helpers: contact filtering and directions summarisation. */
class AssistantWebToolsTest {

    @Test fun filterContactsMatchesNameAndPhoneCaseInsensitively() {
        val contacts = listOf(
            ContactData("Alice Smith", "555-0100"),
            ContactData("bob jones", "555-0199"),
            ContactData("Carol Alice", "555-0111"),
            ContactData("Dan", "555-ALICE"),
        )
        assertEquals(
            listOf("Alice Smith", "Carol Alice", "Dan"),
            filterContacts("alice", contacts).map { it.name },
        )
        assertEquals(listOf("bob jones"), filterContacts("BOB", contacts).map { it.name })
        assertEquals(listOf("Alice Smith"), filterContacts("0100", contacts).map { it.name })
        assertTrue(filterContacts("zzz", contacts).isEmpty())
        // Cap holds at 10.
        val many = (1..20).map { ContactData("Ann $it", "1$it") }
        assertEquals(10, filterContacts("ann", many).size)
    }

    @Test fun summarizeDirectionsDropsPolyline() {
        val points = (1..500).map { DirectionsLatLng(1.0 + it * 0.001, 2.0) }
        val result = DirectionsResult(
            polyline = points,
            segments = listOf(
                DirectionsSegment(points.take(250), 1.0),
                DirectionsSegment(points.drop(250), 0.4),
            ),
            distanceMeters = 12_340.0,
            durationSeconds = 2_700.0,
        )
        val out = summarizeDirections(result)
        assertEquals("Route: 12.3 km, 45 min, 2 segment(s), 500 point(s)", out)
        // No coordinate text leaks into the model-visible summary.
        assertFalse(out.contains("DirectionsLatLng"), out)
        assertEquals("No route found", summarizeDirections(DirectionsResult(emptyList(), emptyList(), 0.0, 0.0)))
    }
}
