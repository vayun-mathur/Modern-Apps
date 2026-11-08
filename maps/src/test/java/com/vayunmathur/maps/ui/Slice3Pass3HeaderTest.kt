package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.GoogleReview
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 pass 3: poiTabs/resolvePoiSection + header enrichment branches. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Slice3Pass3HeaderTest {

    @get:Rule val rule = createComposeRule()

    private fun place(
        name: String = "Blue Bottle",
        rating: Double? = null,
        reviewCount: Int? = null,
    ) = SpecificFeature.GenericPlace(
        name, null, null, null, GeoPoint(-122.4, 37.8),
        rating = rating, reviewCount = reviewCount,
    )

    private class RecordingActions : PlacePanelActions {
        var section: PoiSection? = null
        override fun setPoiSection(section: PoiSection) { this.section = section }
    }

    // --- pure tab logic ------------------------------------------------------

    @Test fun tabsAlwaysOfferDetailsOnlyWithoutPoi() {
        assertEquals(listOf(PoiSection.DETAILS), poiTabs(null))
        assertEquals(listOf(PoiSection.DETAILS), poiTabs(GooglePoiInfo()))
    }

    @Test fun tabsAddPhotosAndReviewsWhenPresent() {
        val withPhotos = GooglePoiInfo(photoUrls = listOf("https://x/y.jpg"))
        assertEquals(listOf(PoiSection.DETAILS, PoiSection.PHOTOS), poiTabs(withPhotos))
        val withReview = GooglePoiInfo(featuredReview = "Great")
        assertEquals(listOf(PoiSection.DETAILS, PoiSection.REVIEWS), poiTabs(withReview))
        val full = GooglePoiInfo(
            photoUrls = listOf("https://x/y.jpg"),
            reviews = listOf(GoogleReview("Ann", null, 5, null, "Great")),
        )
        assertEquals(
            listOf(PoiSection.DETAILS, PoiSection.PHOTOS, PoiSection.REVIEWS),
            poiTabs(full),
        )
    }

    @Test fun resolveFallsBackToDetailsWhenTabVanishes() {
        assertEquals(PoiSection.DETAILS, resolvePoiSection(PoiSection.PHOTOS, null))
        assertEquals(PoiSection.DETAILS, resolvePoiSection(PoiSection.REVIEWS, GooglePoiInfo()))
        val poi = GooglePoiInfo(photoUrls = listOf("https://x/y.jpg"))
        assertEquals(PoiSection.PHOTOS, resolvePoiSection(PoiSection.PHOTOS, poi))
        assertEquals(PoiSection.DETAILS, resolvePoiSection(PoiSection.DETAILS, null))
    }

    // --- header rendering branches --------------------------------------------

    @Test fun headerWithoutPoiShowsNameOnlyAndNoTabs() {
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = PlacePanelState(),
                    panelActions = PlacePanelActions.Noop,
                    inactiveNavigation = null,
                    feature = place(),
                    requestDirections = {},
                )
            }
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.onNodeWithText("Photos", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Reviews", substring = true).assertDoesNotExist()
    }

    @Test fun headerShowsSubtitleRatingAndOpenStatus() {
        val poi = GooglePoiInfo(
            category = "Cafe", priceText = "$$", rating = 4.5, reviewCount = 230,
            statusText = "Open · Closes 9 PM", openNow = true,
            photoUrls = listOf("https://x/y.jpg"),
        )
        val actions = RecordingActions()
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = PlacePanelState(poi = poi),
                    panelActions = actions,
                    inactiveNavigation = null,
                    feature = place(),
                    requestDirections = {},
                )
            }
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.onNodeWithText("Cafe · $$", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open · Closes 9 PM", substring = true).assertIsDisplayed()
        // Photos tab is offered and clicking it notifies the actions.
        rule.onNodeWithText("Photos", substring = true).assertIsDisplayed().performClick()
        assertEquals(PoiSection.PHOTOS, actions.section)
    }

    @Test fun headerShowsClosedStatus() {
        val poi = GooglePoiInfo(statusText = "Closed · Opens 8 AM", openNow = false)
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = PlacePanelState(poi = poi),
                    panelActions = PlacePanelActions.Noop,
                    inactiveNavigation = null,
                    feature = place(),
                    requestDirections = {},
                )
            }
        }
        rule.onNodeWithText("Closed · Opens 8 AM", substring = true).assertIsDisplayed()
    }

    @Test fun headerPrefersOfflineRatingOverPoi() {
        // Archive rating wins so the header works with Google off.
        val poi = GooglePoiInfo(rating = 1.0, reviewCount = 3)
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = PlacePanelState(poi = poi),
                    panelActions = PlacePanelActions.Noop,
                    inactiveNavigation = null,
                    feature = place(rating = 4.9, reviewCount = 999),
                    requestDirections = {},
                )
            }
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.onNodeWithText("999", substring = true).assertIsDisplayed()
    }

    @Test fun restaurantHeaderRenders() {
        val restaurant = SpecificFeature.Restaurant(
            "Pizza Place", null, null, null, null, GeoPoint(-122.4, 37.8),
        )
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = PlacePanelState(),
                    panelActions = PlacePanelActions.Noop,
                    inactiveNavigation = null,
                    feature = restaurant,
                    requestDirections = {},
                )
            }
        }
        rule.onNodeWithText("Pizza Place").assertIsDisplayed()
    }
}
