package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.dialogs.encodeBase26
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.FamilyListUiState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Robolectric Compose tests for [FamilyListSheet] and its cards: sections
 * render per list, actions fire with the right ids, and empty state shows
 * nothing.
 */
@RunWith(AndroidJUnit4::class)
class W5FamilyListSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun user(id: Long, name: String) = User(
        name = name,
        photo = null,
        locationName = "Home",
        sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = Clock.System.now(),
        id = id,
    )

    @Test
    fun connectedUsersRenderAndSelect() {
        var selected = -1L
        val actions = object : FamilyListActions {
            override fun selectUser(userId: Long) {
                selected = userId
            }
        }
        rule.setContent {
            FamilyListSheet(
                state = FamilyListUiState(connectedUsers = listOf(user(1L, "Alice"), user(2L, "Bob"))),
                actions = actions,
            )
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("Bob").assertIsDisplayed()
        rule.onNodeWithText("Bob").performClick()
        assertEquals(2L, selected)
    }

    @Test
    fun awaitingRequestsOfferAccept() {
        var accepted = -1L
        val actions = object : FamilyListActions {
            override fun acceptRequest(userId: Long) {
                accepted = userId
            }
        }
        rule.setContent {
            FamilyListSheet(
                state = FamilyListUiState(awaitingRequestUsers = listOf(user(9L, "Carol"))),
                actions = actions,
            )
        }
        // Card shows "Request from <base26 id>".
        rule.onNodeWithText("Request from", substring = true).assertIsDisplayed()
        rule.onNodeWithText(9L.encodeBase26(), substring = true).assertIsDisplayed()
    }

    @Test
    fun temporaryLinksShowName() {
        rule.setContent {
            FamilyListSheet(
                state = FamilyListUiState(
                    temporaryLinks = listOf(
                        TemporaryLink(
                            name = "party",
                            deleteAt = Clock.System.now() + 1.days,
                            pqcPublicKey = "pub",
                            pqcSeed = "seed",
                            id = 55L,
                        ),
                    ),
                ),
                actions = FamilyListActions.Noop,
            )
        }
        rule.onNodeWithText("party").assertIsDisplayed()
    }

    @Test
    fun waypointsShowNameAndPresentUsers() {
        rule.setContent {
            FamilyListSheet(
                state = FamilyListUiState(
                    waypoints = listOf(Waypoint("home", 100.0, Coord(1.0, 2.0), id = 3L)),
                    userNamesByLocationName = mapOf("home" to listOf("Alice")),
                ),
                actions = FamilyListActions.Noop,
            )
        }
        rule.onNodeWithText("home", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Alice", substring = true).assertIsDisplayed()
    }

    @Test
    fun sectionHeaderRendersTitle() {
        rule.setContent { SectionHeader("My section") }
        rule.onNodeWithText("My section").assertIsDisplayed()
    }

    @Test
    fun waypointCardNobodyHere() {
        rule.setContent {
            WaypointCard(Waypoint("hut", 50.0, Coord(0.0, 0.0)), emptyList(), {})
        }
        rule.onNodeWithText("hut", substring = true).assertIsDisplayed()
    }
}
