package com.vayunmathur.findfamily.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.dialogs.DropdownField
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.FamilyListUiState
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.MainPageUiState
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.PersonUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** MainPageContent chrome in its main states, plus the expiry DropdownField. */
@RunWith(RobolectricTestRunner::class)
class MainPageContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun user(id: Long, name: String) = User(
        name = name, photo = null, locationName = "Home",
        sendingEnabled = true, requestStatus = RequestStatus.MUTUAL_CONNECTION, id = id)

    private class RecordingActions : MainPageActions {
        var addPerson = 0
        var addLink = 0
        var clear = 0
        override fun addPerson() { addPerson++ }
        override fun addLink() { addLink++ }
        override fun clearSelection() { clear++ }
    }

    private fun content(state: MainPageUiState, actions: MainPageActions) {
        composeRule.setContent {
            DynamicTheme {
                MainPageContent(
                    state = state,
                    familyActions = FamilyListActions.Noop,
                    personActions = PersonActions.Noop,
                    actions = actions,
                    map = { Text("MAP-SLOT") })
            }
        }
    }

    @Test fun nothingSelectedShowsFamilySheetAndMap() {
        val state = MainPageUiState(
            familyList = FamilyListUiState(connectedUsers = listOf(user(1L, "Ann"))))
        content(state, RecordingActions())
        composeRule.onNodeWithText("MAP-SLOT").assertIsDisplayed()
        composeRule.onNodeWithText("Ann").assertIsDisplayed()
    }

    @Test fun personSelectedShowsPersonSheet() {
        val ann = user(2L, "Ann")
        val state = MainPageUiState(
            selectedUserId = 2L,
            selectedUser = ann,
            person = PersonUiState(user = ann),
            familyList = FamilyListUiState(connectedUsers = listOf(ann)))
        content(state, RecordingActions())
        composeRule.onNodeWithText("Ann").assertIsDisplayed()
    }

    @Test fun waypointSelectedShowsWaypointForm() {
        val state = MainPageUiState(
            selectedWaypointId = 5L,
            waypointName = "Home",
            waypointRange = "150",
            familyList = FamilyListUiState(
                waypoints = listOf(Waypoint("Home", 150.0, Coord(1.0, 2.0), id = 5L))))
        content(state, RecordingActions())
        composeRule.onNodeWithText("MAP-SLOT").assertIsDisplayed()
    }

    @Test fun dropdownFieldSelectsOption() {
        var value = "1 hour"
        composeRule.setContent {
            DynamicTheme {
                DropdownField(
                    value = value,
                    setValue = { value = it },
                    options = listOf("1 hour", "1 day", "1 week"))
            }
        }
        composeRule.onNodeWithText("1 hour").assertIsDisplayed()
        composeRule.onNodeWithText("1 hour").performClick()
        composeRule.onNodeWithText("1 week").assertIsDisplayed()
        composeRule.onNodeWithText("1 week").performClick()
        assertEquals("1 week", value)
    }
}
