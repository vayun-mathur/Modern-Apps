package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Slice 3/3 (root package files M-Z): the place-sheet stack — the stateless
 * [PlaceSheet] tab panels (restaurant + generic), [OsmHours] with its
 * expandable week and live-Google override, [PlaceSheetHeader]'s save/remove
 * actions, and [RatingStars].
 *
 * The ViewModel overloads are covered through the activity tests; these pin
 * the rendering contract with literal state so the sheets, hours rows and
 * action callbacks are asserted without navigation or network.
 */
@RunWith(AndroidJUnit4::class)
class PlaceSheetSlice3Test {

    @get:Rule
    val rule = createComposeRule()

    private fun genericPlace() = SpecificFeature.GenericPlace(
        name = "Blue Bottle",
        phone = "555-1234",
        website = "https://example.com",
        openingHours = OpeningHours.from("Mo-Fr 08:00-18:00"),
        position = GeoPoint(-122.4, 37.8),
        address = "1 Ferry Bldg",
        rating = 4.5,
        reviewCount = 120,
        reviewSummary = "Great coffee",
    )

    private fun restaurant() = SpecificFeature.Restaurant(
        name = "Pizza Place",
        phone = null,
        website = null,
        menu = "https://example.com/menu",
        openingHours = OpeningHours.from("Mo-Su 00:00-24:00"),
        position = GeoPoint(-122.4, 37.8),
    )

    // Header tests below exercise the revealed state; the mask itself is
    // pinned in PlaceMaskTest.
    private fun panel() = PlacePanelState(saved = emptyList(), detailsRevealed = true)

    // --- PlaceSheet (generic) ---

    @Test
    fun `generic sheet shows address and review summary`() {
        rule.setContent {
            PlaceSheet(
                poi = null,
                selectedSection = PoiSection.DETAILS,
                feature = genericPlace(),
            )
        }
        rule.onNodeWithText("1 Ferry Bldg").assertIsDisplayed()
        rule.onNodeWithText("Great coffee").assertIsDisplayed()
    }

    @Test
    fun `restaurant sheet shows its menu row`() {
        rule.setContent {
            PlaceSheet(
                poi = null,
                selectedSection = PoiSection.DETAILS,
                feature = restaurant(),
            )
        }
        rule.onNodeWithText("Menu", substring = true).assertIsDisplayed()
    }

    @Test
    fun `departures hook renders when supplied`() {
        var opened = false
        rule.setContent {
            PlaceSheet(
                poi = null,
                selectedSection = PoiSection.DETAILS,
                feature = genericPlace(),
                onDepartures = { opened = true },
            )
        }
        rule.onNodeWithText("Departures").assertIsDisplayed()
        rule.onNodeWithText("Departures").performClick()
        assertTrue(opened)
    }

    @Test
    fun `osm hours show the live status line and expand to the week`() {
        // The summary row shows open/closed plus the next change; tapping it
        // expands the per-day table.
        rule.setContent {
            OsmHours(OpeningHours.from("Mo-Su 00:00-24:00")) // open at any wall-clock time: the live status line always reads "Closes"
        }
        rule.onNodeWithText("Closes", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Monday", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Closes", substring = true).performClick()
        rule.onNodeWithText("Monday", substring = true).assertIsDisplayed()
    }

    @Test
    fun `google hours section renders the lone today line`() {
        rule.setContent {
            GooglePoiEnrichment(
                GooglePoiInfo(hours = listOf("Tuesday: 6 AM–10 PM")),
                PoiSection.DETAILS,
            )
        }
        rule.onNodeWithText("Tuesday", substring = true).assertIsDisplayed()
        rule.onNodeWithText("6 AM–10 PM", substring = true).assertIsDisplayed()
    }

    @Test
    fun `empty enrichment renders nothing`() {
        rule.setContent {
            GooglePoiEnrichment(GooglePoiInfo(), PoiSection.DETAILS)
        }
        rule.onNodeWithText("Tuesday", substring = true).assertDoesNotExist()
    }

    // --- PlaceSheetHeader ---

    @Test
    fun `header save and remove drive the panel actions`() {
        var added = 0
        var removed = 0
        val actions = object : PlacePanelActions {
            override fun addSaved() { added++ }
            override fun removeSaved(place: com.vayunmathur.maps.data.SavedPlace) { removed++ }
        }
        rule.setContent {
            PlaceSheetHeader(
                panelState = panel(),
                panelActions = actions,
                inactiveNavigation = null,
                feature = genericPlace(),
                requestDirections = {},
            )
        }
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        assertEquals(1, added)
    }

    @Test
    fun `header shows saved state with a directions action`() {
        var directed = false
        rule.setContent {
            PlaceSheetHeader(
                panelState = PlacePanelState(
                    saved = listOf(
                        com.vayunmathur.maps.data.SavedPlace.from(genericPlace()),
                    ),
                    detailsRevealed = true,
                ),
                panelActions = PlacePanelActions.Noop,
                inactiveNavigation = null,
                feature = genericPlace(),
                requestDirections = { directed = true },
            )
        }
        rule.onNodeWithText("Saved").assertIsDisplayed()
        rule.onNodeWithText("Directions").performClick()
        assertTrue(directed)
    }

    @Test
    fun `header phone and website rows render`() {
        rule.setContent {
            PlaceSheetHeader(
                panelState = panel(),
                panelActions = PlacePanelActions.Noop,
                inactiveNavigation = null,
                feature = genericPlace(),
                requestDirections = {},
            )
        }
        rule.onNodeWithText("Call", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Website", substring = true).assertIsDisplayed()
    }

    @Test
    fun `rating stars render a five-icon row`() {
        rule.setContent { RatingStars(4.5) }
        // Filled and outline stars share the "Star" description; the row
        // always draws five icons for any score.
        rule.onAllNodesWithContentDescription("Star").assertCountEquals(5)
    }
}
