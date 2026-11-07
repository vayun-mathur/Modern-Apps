package com.vayunmathur.taxi.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.R
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pass 3: RideScreen's location-driven effects with a granted permission
 * and a last-known fix — device pickup resolution, search debounce, and
 * the reset-pickup action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPass3RideScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun rideScreenResolvesPickupFromLastKnownLocation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        shadowOf(manager).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 37.7749
                longitude = -122.4194
            },
        )
        rule.setContent { RideScreen() }
        // The pickup field fills with the reverse-geocode fallback name.
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Current location").fetchSemanticsNodes().isNotEmpty()
        }
        // Typing a destination runs the debounced (empty) geocoder search
        // without crashing and keeps the screen composed.
        rule.onNodeWithText(context.getString(R.string.destination))
            .performTextInput("Ferry Building")
        rule.waitForIdle()
        // Reset-pickup re-anchors to the device location.
        assertTrue(
            rule.onAllNodesWithContentDescription("My location").fetchSemanticsNodes().isNotEmpty(),
        )
        rule.onAllNodesWithContentDescription("My location")[1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Current location").assertExists()
    }
}
