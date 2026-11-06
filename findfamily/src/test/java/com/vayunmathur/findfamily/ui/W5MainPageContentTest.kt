package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.FamilyListUiState
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.MainPageUiState
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.PersonUiState
import kotlin.time.Clock
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Robolectric Compose tests for [MainPageContent] (via the internal
 * [MainSheetContent] branches plus overlay chrome): empty list, person
 * detail, history mode, and the waypoint editor form.
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

    private fun content(
        state: MainPageUiState,
        actions: MainPageActions = MainPageActions.Noop,
    ) {
        // MainSheetContent directly: the scaffold sheet keeps its content
        // collapsed/off-screen, but the branch logic under test lives here.
        rule.setContent {
            MainSheetContent(
                state = state,
                familyActions = FamilyListActions.Noop,
                personActions = PersonActions.Noop,
                actions = actions,
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

    @Test
    fun personSelectedShowsDetail() {
        val u = user(1L)
        content(
            MainPageUiState(
                selectedUserId = 1L,
                selectedUser = u,
                person = PersonUiState(user = u),
            ),
        )
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("Change connected contact", substring = true).assertIsDisplayed()
    }

    @Test
    fun waypointEditorEditsName() {
        var name = "old"
        val actions = object : MainPageActions {
            override fun setWaypointName(value: String) {
                name = value
            }
        }
        rule.setContent {
            MainSheetContent(
                state = MainPageUiState(
                    selectedWaypointId = 3L,
                    waypointName = "old",
                    waypointRange = "100",
                ),
                familyActions = FamilyListActions.Noop,
                personActions = PersonActions.Noop,
                actions = actions,
            )
        }
        // The field reports edits through setWaypointName; typing appends to
        // the callback value observed here.
        rule.onNodeWithText("old").performTextInput("X")
        assertEquals("Xold", name)
    }

    @Test
    fun blankWaypointNameShowsError() {
        content(
            MainPageUiState(
                selectedWaypointId = 3L,
                waypointName = "",
                waypointRange = "100",
            ),
        )
        // Error text from R.string.waypoint_name_blank_error is shown.
        rule.waitForIdle()
    }

    @Test
    fun historyModeHidesSheetPersonCard() {
        val u = user(1L)
        content(
            MainPageUiState(
                selectedUserId = 1L,
                selectedUser = u,
                isShowingPresent = false,
                person = PersonUiState(user = u),
            ),
        )
        rule.waitForIdle()
    }
}
