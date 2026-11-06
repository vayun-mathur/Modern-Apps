package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.PersonUiState
import kotlin.time.Clock
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Robolectric Compose tests for [PersonDetailSheet]: others get sharing
 * controls + no-show entry, self hides them; switch toggles sharing.
 */
@RunWith(AndroidJUnit4::class)
class W5PersonDetailSheetTest {

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

    @Test
    fun otherPersonShowsSharingControls() {
        // Networking.userid defaults to 0L and cannot be set from tests;
        // any nonzero user id is therefore "another person".
                rule.setContent {
            PersonDetailSheet(
                state = PersonUiState(user = user(1L)),
                actions = PersonActions.Noop,
            )
        }
        rule.onNodeWithText("Share your location with this person", substring = true)
            .assertIsDisplayed()
        rule.onNodeWithText("Watch for no-show", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Change connected contact", substring = true).assertIsDisplayed()
    }

    @Test
    fun selfHidesSharingControls() {
        rule.setContent {
            PersonDetailSheet(
                state = PersonUiState(user = user(0L)),
                actions = PersonActions.Noop,
            )
        }
        rule.onNodeWithText("Change connected contact", substring = true).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("Share your location with this person", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun pausedNoteShowsWhenGloballyDisabled() {
                rule.setContent {
            PersonDetailSheet(
                state = PersonUiState(user = user(1L), sharingGloballyEnabled = false),
                actions = PersonActions.Noop,
            )
        }
        rule.onNodeWithText("Sharing is paused", substring = true).assertIsDisplayed()
    }

    @Test
    fun noShowButtonOpensDialog() {
                rule.setContent {
            PersonDetailSheet(
                state = PersonUiState(user = user(1L)),
                actions = PersonActions.Noop,
            )
        }
        rule.onNodeWithText("Watch for no-show", substring = true).performClick()
        // The dialog opens with its date/time pickers unset.
        rule.onNodeWithText("Pick date", substring = true).assertIsDisplayed()
    }
}
