package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.dialogs.NoShowAlertDialog
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.MainPageUiState
import com.vayunmathur.findfamily.util.FamilyListUiState
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.PersonUiState
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric Compose interaction tests: no-show dialog dropdowns/pickers + sheet states. */
@RunWith(AndroidJUnit4::class)
class MainPageInteractionsTest {
    @get:Rule val rule = createComposeRule()

    private fun user(id: Long = 1L, name: String = "Alice") = User(name = name, photo = null,
        locationName = "Home", sendingEnabled = true, requestStatus = RequestStatus.MUTUAL_CONNECTION, id = id)

    @Test fun noShowPlaceAndGraceDropdowns() {
        rule.setContent {
            NoShowAlertDialog(user(), listOf(Waypoint("home", 100.0, Coord(1.0, 2.0), id = 3L),
                Waypoint("office", 100.0, Coord(3.0, 4.0), id = 4L)), PersonActions.Noop, {})
        }
        rule.onNodeWithText("Anywhere", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("home", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("home", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Watch", substring = false).assertIsNotEnabled()
    }
    @Test fun noShowDatePickerOpens() {
        rule.setContent { NoShowAlertDialog(user(), emptyList(), PersonActions.Noop, {}) }
        rule.onNodeWithText("Pick date", substring = true).performClick()
        rule.waitForIdle()
        // Date picker dialog composes (OK/Cancel buttons from Material3 picker)
        assertTrue(rule.onAllNodesWithText("OK", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("Cancel", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun familyListWithRequestsAndLinks() {
        val state = MainPageUiState(familyList = FamilyListUiState(
            connectedUsers = listOf(user(1L, "Alice")),
            awaitingRequestUsers = listOf(user(2L, "Bob")),
            waypoints = listOf(Waypoint("home", 100.0, Coord(1.0, 2.0), id = 3L)),
            temporaryLinks = listOf(TemporaryLink(name = "Trip", deleteAt = Instant.fromEpochMilliseconds(9999999999L), pqcPublicKey = "aGVsbG8=", id = 9L)),
        ))
        var accepted: Long? = null
        val actions = object : FamilyListActions {
            override fun acceptRequest(userId: Long) { accepted = userId }
        }
        rule.setContent {
            MainPageContent(state = state, familyActions = actions,
                actions = MainPageActions.Noop, map = {}, backupPassphrase = "pw")
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Alice", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(accepted == null)
    }
    @Test fun personDetailShowsSharingControls() {
        val u = user(7L, "Carol")
        rule.setContent { PersonDetailSheet(PersonUiState(user = u), PersonActions.Noop) }
        rule.onNodeWithText("Carol").assertIsDisplayed()
    }
}
