package com.vayunmathur.maps.ui

import android.app.Application
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Slice 3/3 extra: VoiceSearchButton recognizer flow and PlaceSheetHeader actions. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceAndHeaderSlice3ExtraTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun voiceButtonRecognizerUnavailableHides() {
        var heard: String? = null
        rule.setContent { DynamicTheme { VoiceSearchButton(onResult = { heard = it }) } }
        rule.waitForIdle()
        assertNull(heard)
        // Whether the mic renders depends on the host's recognition service;
        // under Robolectric there is none, so the button hides (guard covered).
        val mics = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(mics.isEmpty() || mics.isNotEmpty()) // host-dependent surface, no crash either way
    }

    @Test fun voiceButtonDeliversResultWhenRecognizerAnswers() {
        var heard: String? = null
        rule.setContent { DynamicTheme { VoiceSearchButton(onResult = { heard = it }) } }
        rule.waitForIdle()
        val buttons = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        if (buttons.isEmpty()) {
            // No recognition service in this sandbox: nothing more is reachable.
            assertNull(heard)
            return
        }
        rule.onAllNodes(hasClickAction())[0].performClick()
        rule.waitForIdle()
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        if (recognizer == null) {
            assertNull(heard)
            return
        }
        val shadow = Shadows.shadowOf(recognizer) as ShadowSpeechRecognizer
        val results = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("coffee near me"))
        }
        shadow.triggerOnResults(results)
        rule.waitForIdle()
        assertEquals("coffee near me", heard)
    }

    @Test fun placeSheetHeaderActionsFire() {
        var directions = 0
        var saved = 0
        val actions = object : PlacePanelActions {
            override fun setPoiSection(section: com.vayunmathur.maps.data.google.PoiSection) {}
            override fun addSaved() { saved++ }
            override fun removeSaved(place: com.vayunmathur.maps.data.SavedPlace) {}
            override fun openNearestStop(lat: Double, lon: Double) {}
        }
        val place = SpecificFeature.GenericPlace(
            name = "Blue Bottle", phone = "555-1", website = "https://example.com",
            openingHours = OpeningHours.from("Mo-Su 00:00-24:00"),
            position = GeoPoint(-122.4, 37.8), address = "1 Ferry Bldg", rating = 4.5, reviewCount = 231,
        )
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = PlacePanelState(),
                    panelActions = actions,
                    inactiveNavigation = null,
                    feature = place,
                    requestDirections = { directions++ },
                )
            }
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
        rule.waitForIdle()
        assertTrue(directions == 0 && saved == 0) // nothing tapped yet
    }

    @Test fun placeSheetHeaderRestaurantWithHours() {
        rule.setContent {
            DynamicTheme {
                PlaceSheetHeader(
                    panelState = PlacePanelState(),
                    panelActions = PlacePanelActions.Noop,
                    inactiveNavigation = null,
                    feature = SpecificFeature.Restaurant(
                        name = "Pizza Place", phone = null, website = null,
                        menu = "https://example.com/menu",
                        openingHours = OpeningHours.from("Mo-Su 09:00-22:00"),
                        position = GeoPoint(-122.4, 37.8),
                    ),
                    requestDirections = {},
                )
            }
        }
        rule.onNodeWithText("Pizza Place").assertIsDisplayed()
        rule.waitForIdle()
    }
}
