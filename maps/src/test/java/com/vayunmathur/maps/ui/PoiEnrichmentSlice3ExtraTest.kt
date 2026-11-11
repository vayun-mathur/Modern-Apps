package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.google.FuelPrice
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiDayBusyness
import com.vayunmathur.maps.data.google.PoiHourBusyness
import com.vayunmathur.maps.data.google.PoiPopularTimes
import com.vayunmathur.maps.data.google.PoiSection
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 extra: GooglePoiEnrichment section variants. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PoiEnrichmentSlice3ExtraTest {

    @get:Rule val rule = createComposeRule()

    @Test fun detailsWithFuelHoursAndPopularTimes() {
        val info = GooglePoiInfo(
            fuelPrice = FuelPrice("$3.89", "Regular"),
            hours = listOf("Monday: 6 AM–10 PM", "Funday 24 hours"), // second line has no day separator
            popularTimes = PoiPopularTimes(listOf(
                PoiDayBusyness(1, listOf(PoiHourBusyness(6, 20), PoiHourBusyness(7, 80), PoiHourBusyness(8, 120))),
                PoiDayBusyness(3, listOf(PoiHourBusyness(9, 50))),
            )),
        )
        rule.setContent { DynamicTheme { GooglePoiEnrichment(info, PoiSection.DETAILS) } }
        rule.onNodeWithText("$3.89").assertIsDisplayed()
        rule.onNodeWithText("Regular").assertIsDisplayed()
        rule.onNodeWithText("Monday").assertIsDisplayed()
        rule.onNodeWithText("Popular times", substring = true).assertIsDisplayed()
        rule.onRoot().captureToImage()
    }

    @Test fun detailsShowHoursSuppressedAndEmptyPopular() {
        val info = GooglePoiInfo(
            hours = listOf("Tuesday: 9 AM–5 PM"),
            popularTimes = PoiPopularTimes(listOf(PoiDayBusyness(2, emptyList()))),
        )
        rule.setContent { DynamicTheme { GooglePoiEnrichment(info, PoiSection.DETAILS, showHours = false) } }
        // showHours=false hides the hours card; empty-hours popular times render nothing
        rule.onAllNodes(androidx.compose.ui.test.hasText("9 AM–5 PM")).fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "hours should be suppressed")
        }
    }

    @Test fun photosGridOddAndEven() {
        val info = GooglePoiInfo(photoUrls = listOf("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"))
        rule.setContent { DynamicTheme { GooglePoiEnrichment(info, PoiSection.PHOTOS) } }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun emptyInfoRendersNothingInAllSections() {
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    GooglePoiEnrichment(GooglePoiInfo(), PoiSection.DETAILS)
                    GooglePoiEnrichment(GooglePoiInfo(), PoiSection.PHOTOS)
                }
            }
        }
        rule.waitForIdle()
        assertTrue(GooglePoiInfo().isEmpty)
        assertTrue(!GooglePoiInfo(website = "https://x.example").isEmpty)
    }
}
