package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.util.PersonActions
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Robolectric Compose tests for [UserCard] (all four source wordings +
 * click) and [AutoToggleRow] (renders current mode, offers Never).
 */
@RunWith(AndroidJUnit4::class)
class W5UserCardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun user(locationName: String = "Home") = User(
        name = "Alice",
        photo = null,
        locationName = locationName,
        sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = Clock.System.now(),
    )

    private fun fix(source: LocationSource) = LocationValue(
        userid = 1L,
        coord = Coord(1.0, 2.0),
        speed = 0f,
        acc = 5f,
        timestamp = Clock.System.now() - 20.minutes,
        battery = 60f,
        reportedAt = Clock.System.now() - 5.minutes,
        source = source,
    )

    @Test
    fun liveCardShowsUpdatedStatus() {
        rule.setContent { UserCard(user(), fix(LocationSource.LIVE), true, {}) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("Updated", substring = true).assertIsDisplayed()
    }

    @Test
    fun networkSightingUsesVagueWording() {
        rule.setContent { UserCard(user(), fix(LocationSource.NETWORK_SIGHTING), true, {}) }
        rule.onNodeWithText("Seen by a passing phone", substring = true).assertIsDisplayed()
        rule.onNodeWithText("approximate", substring = true).assertIsDisplayed()
    }

    @Test
    fun shutdownCardShowsSwitchedOff() {
        rule.setContent { UserCard(user(), fix(LocationSource.SHUTDOWN), true, {}) }
        rule.onNodeWithText("Phone switched off", substring = true).assertIsDisplayed()
    }

    @Test
    fun batteryLowCardShowsLowBattery() {
        rule.setContent { UserCard(user(), fix(LocationSource.BATTERY_LOW), true, {}) }
        rule.onNodeWithText("Low battery report", substring = true).assertIsDisplayed()
    }

    @Test
    fun cardWithoutLocationShowsNever() {
        rule.setContent { UserCard(user(), null, true, {}) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun cardClickFires() {
        var clicked = false
        rule.setContent { UserCard(user(), fix(LocationSource.LIVE), true, { clicked = true }) }
        rule.onNodeWithText("Alice").performClick()
        assertTrue(clicked)
    }

    @Test
    fun autoToggleRowRenders() {
        rule.setContent {
            AutoToggleRow(user = user(), waypoints = emptyList(), actions = PersonActions.Noop)
        }
        rule.onNodeWithText("Never", substring = true).assertIsDisplayed()
    }
}
