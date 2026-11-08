package com.vayunmathur.maps.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.ipc.rememberRideEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplication

/** Slice 3/3 pass 2: RouteSheetTabs segments + ride-estimate state. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Slice3Pass2UiTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun modeTabShowsAllLines() {
        rule.setContent {
            DynamicTheme { ModeTab("Drive", "12 min", "3.2 km") }
        }
        rule.onNodeWithText("Drive").assertIsDisplayed()
        rule.onNodeWithText("12 min").assertIsDisplayed()
        rule.onNodeWithText("3.2 km").assertIsDisplayed()
    }

    @Test fun modeTabDropsMissingLines() {
        rule.setContent {
            DynamicTheme { ModeTab("Walk", null, null) }
        }
        rule.onNodeWithText("Walk").assertIsDisplayed()
        rule.onNodeWithText("12 min").assertDoesNotExist()
    }

    @Test fun modeTabShowsOnlyPrimaryWhenSecondaryMissing() {
        rule.setContent {
            DynamicTheme { ModeTab("Transit", "20 min", null) }
        }
        rule.onNodeWithText("Transit").assertIsDisplayed()
        rule.onNodeWithText("20 min").assertIsDisplayed()
    }

    @Test fun rideshareBookingButtonLaunchesTaxiDeepLink() {
        rule.setContent {
            DynamicTheme {
                RideshareBooking(
                    originLat = 37.8, originLng = -122.4, originLabel = "Home",
                    destLat = 37.9, destLng = -122.3, destLabel = "Office",
                )
            }
        }
        rule.onNodeWithText("Book ride").assertIsDisplayed().performClick()
        rule.waitForIdle()
        val started = (Shadows.shadowOf(app) as ShadowApplication).nextStartedActivity
        assertEquals("taxi", started?.data?.scheme)
        assertEquals("book", started?.data?.host)
        assertEquals("37.8", started?.data?.getQueryParameter("pickup_lat"))
        assertEquals("Office", started?.data?.getQueryParameter("dest_label"))
    }

    @Test fun rideEstimateStaysNullWhenTaxiAbsent() {
        var observed: com.vayunmathur.maps.ipc.RideEstimate? = null
        var composed = false
        rule.setContent {
            val state = rememberRideEstimate(
                context = app,
                pickupLat = 37.8, pickupLng = -122.4,
                destLat = 37.9, destLng = -122.3,
                pickupLabel = null, destLabel = null,
            )
            observed = state.value
            composed = true
        }
        rule.waitForIdle()
        assertEquals(true, composed)
        // No taxi app in the sandbox: the estimate degrades to null, no crash.
        assertNull(observed)
    }
}
