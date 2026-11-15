package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.util.PersonActions
import kotlin.time.Duration
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [AutoToggleRow] timer + arrival dropdowns. */
@RunWith(AndroidJUnit4::class)
class AutoToggleRowTest {
    @get:Rule val rule = createComposeRule()

    private fun user(toggleAt: Instant? = null, waypointId: Long? = null) = User(name = "Alice", photo = null,
        locationName = "Home", sendingEnabled = true, requestStatus = RequestStatus.MUTUAL_CONNECTION,
        sharingAutoToggleAt = toggleAt, sharingAutoToggleWaypointId = waypointId, id = 1L)

    @Test fun neverByDefaultAndTimerOptionsListed() {
        var chosen: Duration? = null; var called = false
        val actions = object : PersonActions {
            override fun setUserAutoToggle(user: User, duration: Duration?) { called = true; chosen = duration }
        }
        rule.setContent { AutoToggleRow(user(), emptyList(), actions) }
        rule.onNodeWithText("Never", substring = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("15 min", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("1 hour", substring = true).performClick()
        assertTrue(called); assertEquals(Duration.parse("1h"), chosen)
    }
    @Test fun arrivalOptionsListWaypoints() {
        rule.setContent { AutoToggleRow(user(), listOf(Waypoint("home", 100.0, Coord(1.0, 2.0), id = 3L)), PersonActions.Noop) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Never", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
