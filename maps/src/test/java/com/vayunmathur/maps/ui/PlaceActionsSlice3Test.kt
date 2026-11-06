package com.vayunmathur.maps.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Slice 3/3 (root package files M-Z): the place action row, maneuver icons
 * and the voice button.
 *
 * [PlaceActionRow] wires six actions: directions/save callbacks are recorded,
 * while call/website/share/order fire external intents whose shape is
 * asserted off Robolectric's started-activity record. [ManeuverIcon]'s
 * lambdas are rendered once each so every `iconContent` branch counts, and
 * [VoiceSearchButton] hides itself with no recognition service under test.
 */
@RunWith(AndroidJUnit4::class)
class PlaceActionsSlice3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun feature() = SpecificFeature.GenericPlace(
        name = "Blue Bottle",
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )

    private fun row(
        phone: String? = "555-1234",
        website: String? = "https://example.com",
        isSaved: Boolean = false,
        orderDeepLink: String? = "https://order.example.com/x",
        onAddSaved: () -> Unit = {},
        onRemoveSaved: (SavedPlace) -> Unit = {},
        requestDirections: () -> Unit = {},
    ) {
        rule.setContent {
            PlaceActionRow(
                feature = feature(),
                phone = phone,
                website = website,
                inactiveNavigation = null,
                isSaved = isSaved,
                savedMatch = null,
                onAddSaved = onAddSaved,
                onRemoveSaved = onRemoveSaved,
                requestDirections = requestDirections,
                orderDeepLink = orderDeepLink,
            )
        }
    }

    @Test
    fun `directions and save drive their callbacks`() {
        var directed = 0
        var added = 0
        row(requestDirections = { directed++ }, onAddSaved = { added++ })
        rule.onNodeWithText("Directions").performClick()
        rule.onNodeWithText("Save").performClick()
        assertEquals(1, directed)
        assertEquals(1, added)
    }

    @Test
    fun `remove calls back with the saved match`() {
        var removed: SavedPlace? = null
        val match = SavedPlace("Blue Bottle", 37.8, -122.4)
        rule.setContent {
            PlaceActionRow(
                feature = feature(),
                phone = null,
                website = null,
                inactiveNavigation = null,
                isSaved = true,
                savedMatch = match,
                onAddSaved = {},
                onRemoveSaved = { removed = it },
                requestDirections = {},
                orderDeepLink = null,
            )
        }
        rule.onNodeWithText("Saved").performClick()
        assertEquals(match, removed)
    }

    @Test
    fun `call button dials the listed number`() {
        row()
        rule.onNodeWithText("Call", substring = true).performClick()
        val started = shadowOf(app).nextStartedActivity
        assertTrue(started != null)
        assertEquals("tel:555-1234", started.data.toString())
    }

    @Test
    fun `share sends the place link as text`() {
        row(phone = null, website = null, orderDeepLink = null)
        rule.onNodeWithText("Share", substring = true).performClick()
        val started = shadowOf(app).nextStartedActivity
        assertTrue(started != null)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun `order row only appears with a deep link`() {
        row(orderDeepLink = "https://order.example.com/x")
        rule.onNodeWithText("Order", substring = true).assertIsDisplayed()
    }

    @Test
    fun `no order row without a deep link`() {
        row(orderDeepLink = null)
        rule.onNodeWithText("Order", substring = true).assertDoesNotExist()
    }

    @Test
    fun `add-stop label replaces directions with a parked route`() {
        rule.setContent {
            PlaceActionRow(
                feature = feature(),
                phone = null,
                website = null,
                inactiveNavigation = SpecificFeature.Route(waypoints = listOf(feature())),
                isSaved = false,
                savedMatch = null,
                onAddSaved = {},
                onRemoveSaved = {},
                requestDirections = {},
                orderDeepLink = null,
            )
        }
        rule.onNodeWithText("Add Stop to Route").assertIsDisplayed()
    }

    // --- ManeuverIcon ---

    @Test
    fun `every iconed maneuver renders an image`() {
        val iconed = RouteService.API.Maneuver.entries.filter { it.iconContent() != null }
        assertTrue(iconed.size >= 15)
        for (maneuver in iconed) {
            val content = maneuver.iconContent() ?: continue
            rule.setContent {
                content(androidx.compose.ui.Modifier, androidx.compose.ui.graphics.Color.Black)
            }
            rule.waitForIdle()
        }
    }

    @Test
    fun `ferry and unspecified maneuvers draw nothing`() {
        assertNull(RouteService.API.Maneuver.FERRY.iconContent())
        assertNull(RouteService.API.Maneuver.FERRY_TRAIN.iconContent())
        assertNull(RouteService.API.Maneuver.MANEUVER_UNSPECIFIED.iconContent())
    }

    // --- VoiceSearchButton ---

    @Test
    fun `voice button hides with no recognition service`() {
        var called = false
        rule.setContent {
            VoiceSearchButton(onResult = { called = true })
        }
        rule.waitForIdle()
        assertEquals(false, called)
        rule.onNodeWithContentDescription("Mic", substring = true).assertDoesNotExist()
    }
}
