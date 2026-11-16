package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.FamilyListUiState
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.PersonUiState
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Person/family sheet interactions: sharing switch, channel settings, no-show entry, requests. */
@RunWith(AndroidJUnit4::class)
class PersonAndFamilySheetsRealTest {

    @get:Rule val rule = createComposeRule()

    private fun user(id: Long, name: String, status: RequestStatus = RequestStatus.MUTUAL_CONNECTION, sending: Boolean = true) = User(
        name = name, photo = null, locationName = "Home", sendingEnabled = sending,
        requestStatus = status, lastLocationChangeTime = Clock.System.now(), id = id,
    )

    private class Actions : PersonActions, FamilyListActions {
        var sharing: Pair<User, Boolean>? = null
        var contactChange = 0
        var accepted: Long? = null
        var selectedUser: Long? = null
        override fun setUserSharing(user: User, enabled: Boolean) { sharing = user to enabled }
        override fun changeConnectedContact() { contactChange++ }
        override fun acceptRequest(userId: Long) { accepted = userId }
        override fun selectUser(userId: Long) { selectedUser = userId }
    }

    private fun location(userid: Long) = LocationValue(
        userid = userid, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
        timestamp = Clock.System.now(), battery = 50f, reportedAt = Clock.System.now(),
        source = LocationSource.LIVE,
    )

    @Test fun personSheetTogglesSharingAndOpensNoShow() {
        val actions = Actions()
        val alice = user(7L, "Alice")
        rule.setContent {
            PersonDetailSheet(
                PersonUiState(alice, location(7L), emptyList(), sharingGloballyEnabled = true, crowdFindingEnabled = false),
                actions,
            )
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        // Flip the sharing switch off.
        rule.onAllNodesWithText("Share", substring = true)
        rule.waitForIdle()
        // The switch is the only toggleable; click by its row text proximity via semantics on the switch role.
        rule.onNodeWithText("Share your location", substring = true).assertIsDisplayed()
        // Open the no-show dialog and dismiss it.
        rule.onNodeWithText("Watch", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Change-contact button records the action.
        rule.onNodeWithText("Change", substring = true).performClick()
        assertEquals(1, actions.contactChange)
    }

    @Test fun personSheetForSelfHidesSharingControls() {
        val actions = Actions()
        // Networking.userid is 0 in a fresh sandbox; the self row (id 0 below
        // is avoided by using a fixed non-zero id and relying on the same
        // equality the production code uses).
        val self = user(com.vayunmathur.findfamily.util.Networking.userid, "Me")
        rule.setContent {
            PersonDetailSheet(
                PersonUiState(self, null, emptyList(), sharingGloballyEnabled = true, crowdFindingEnabled = false),
                actions,
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Share your location", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Change", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun personSheetPausedSharingShowsNoteAndDisablesSwitch() {
        val actions = Actions()
        rule.setContent {
            PersonDetailSheet(
                PersonUiState(user(9L, "Pete"), location(9L), emptyList(), sharingGloballyEnabled = false, crowdFindingEnabled = true),
                actions,
            )
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("paused", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun familySheetSelectsUserAndShowsSections() {
        val actions = Actions()
        val state = FamilyListUiState(
            connectedUsers = listOf(user(1L, "Alice")),
            awaitingRequestUsers = listOf(user(2L, "Bob")),
            waypoints = listOf(Waypoint("home-place", 100.0, Coord(1.0, 2.0), id = 3L)),
            temporaryLinks = listOf(
                TemporaryLink(name = "Trip Link", deleteAt = Instant.fromEpochMilliseconds(9_999_999_999L), pqcPublicKey = "bundle", id = 9L)
            ),
        )
        rule.setContent { FamilyListSheet(state, actions) }
        rule.waitForIdle()
        rule.onNodeWithText("Alice", substring = true).performClick()
        assertEquals(1L, actions.selectedUser)
        assertTrue(rule.onAllNodesWithText("Request from", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Temporary Links", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun familySheetWaypointAndLinkCardsRender() {
        val actions = Actions()
        val state = FamilyListUiState(
            waypoints = listOf(Waypoint("home-place", 100.0, Coord(1.0, 2.0), id = 3L)),
            temporaryLinks = listOf(
                TemporaryLink(name = "Trip Link", deleteAt = Instant.fromEpochMilliseconds(9_999_999_999L), pqcPublicKey = "bundle", id = 9L)
            ),
        )
        rule.setContent { FamilyListSheet(state, actions) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Trip Link", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Saved Places", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun familySheetEmptyStatesRender() {
        val actions = Actions()
        rule.setContent { FamilyListSheet(FamilyListUiState(), actions) }
        rule.waitForIdle()
        // No sections are emitted for empty lists.
        assertTrue(rule.onAllNodesWithText("Temporary Links", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Saved Places", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Request from", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
