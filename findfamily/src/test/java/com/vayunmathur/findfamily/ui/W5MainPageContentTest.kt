package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.platform.FamilyListActions
import com.vayunmathur.findfamily.platform.FamilyListUiState
import com.vayunmathur.findfamily.platform.MainPageUiState
import kotlin.time.Clock
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test

/**
 * Robolectric Compose tests for the in-page sheet ([MainSheetContent]):
 * it always renders the family list now — person/waypoint sheets are the
 * `Route.PersonSheet` / `Route.WaypointSheet` destinations, covered by
 * navigation-level tests.
 */
@RunWith(AndroidJUnit4::class)
class W5MainPageContentTest {

    @get:Rule
    val rule = createComposeRule()

    private fun user(id: Long) = User(
        name = "Alice",
        photo = null,
        locationName = "Home",
        sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = Clock.System.now(),
        id = id,
    )

    private fun content(state: MainPageUiState) {
        rule.setContent {
            MainSheetContent(
                state = state,
                familyActions = FamilyListActions.Noop,
            )
        }
    }

    @Test
    fun emptyFamilyShowsListWithoutCrash() {
        content(MainPageUiState())
        rule.waitForIdle()
    }

    @Test
    fun familyListRendersConnectedUser() {
        content(
            MainPageUiState(
                familyList = FamilyListUiState(connectedUsers = listOf(user(1L))),
            ),
        )
        rule.onNodeWithText("Alice").assertIsDisplayed()
    }
}
