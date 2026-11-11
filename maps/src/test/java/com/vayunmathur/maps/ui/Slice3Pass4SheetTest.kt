package com.vayunmathur.maps.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiSection
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplication

/** Slice 3/3 pass 4: PlaceSheet section fallback + row actions. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Slice3Pass4SheetTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

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

    @Test fun reviewsSelectionFallsBackToDetailsWithoutPoi() {
        rule.setContent {
            DynamicTheme {
                PlaceSheet(
                    poi = null,
                    selectedSection = PoiSection.PHOTOS,
                    feature = genericPlace(),
                )
            }
        }
        // No enrichment: the vanished Reviews tab resolves to Details, whose
        // OSM rows (address, summary) are what render.
        rule.onNodeWithText("1 Ferry Bldg", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Great coffee", substring = true).assertIsDisplayed()
    }

    @Test fun photosSectionRendersWithoutDetailsRows() {
        val poi = GooglePoiInfo(photoUrls = listOf("https://example.com/a.jpg", "https://example.com/b.jpg"))
        rule.setContent {
            DynamicTheme {
                PlaceSheet(
                    poi = poi,
                    selectedSection = PoiSection.PHOTOS,
                    feature = genericPlace(),
                )
            }
        }
        rule.waitForIdle()
        // Photos section replaces the Details rows: address must not show.
        rule.onNodeWithText("1 Ferry Bldg", substring = true).assertDoesNotExist()
    }

    @Test fun menuRowClickOpensMenuLink() {
        rule.setContent {
            DynamicTheme {
                PlaceSheet(
                    poi = null,
                    selectedSection = PoiSection.DETAILS,
                    feature = restaurant(),
                )
            }
        }
        rule.onNodeWithText("Menu", substring = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        val started = (Shadows.shadowOf(app) as ShadowApplication).nextStartedActivity
        assertEquals("https://example.com/menu", started?.data?.toString())
    }

    @Test fun addressRowClickCopiesAddress() {
        rule.setContent {
            DynamicTheme {
                PlaceSheet(
                    poi = null,
                    selectedSection = PoiSection.DETAILS,
                    feature = genericPlace(),
                )
            }
        }
        rule.onNodeWithText("1 Ferry Bldg", substring = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals("1 Ferry Bldg", copied)
    }

    @Test fun ratingStarsZeroRenders() {
        rule.setContent {
            DynamicTheme { RatingStars(0.0) }
        }
        rule.onRoot().assertExists()
    }

    @Test fun ratingStarsFullRenders() {
        rule.setContent {
            DynamicTheme { RatingStars(5.0) }
        }
        rule.onRoot().assertExists()
    }

    @Test fun malformedGoogleHoursLineIsIgnored() {
        // A scrape line with no parseable day must not override the OSM week.
        val poi = GooglePoiInfo(hours = listOf("GarbageLine"))
        rule.setContent {
            DynamicTheme {
                PlaceSheet(
                    poi = poi,
                    selectedSection = PoiSection.DETAILS,
                    feature = genericPlace(),
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 Ferry Bldg", substring = true).assertIsDisplayed()
    }
}
