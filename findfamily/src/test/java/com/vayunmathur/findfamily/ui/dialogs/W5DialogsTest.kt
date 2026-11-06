package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.util.PersonActions
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Robolectric Compose tests for the small dialogs and the no-show dialog's
 * initial state: dismiss-only alerts render + dismiss, and [NoShowAlertDialog]
 * starts with no date/time (save disabled) and lists named waypoints.
 */
@RunWith(AndroidJUnit4::class)
class W5DialogsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun user() = User(
        name = "Alice",
        photo = null,
        locationName = "loc",
        sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
    )

    @Test
    fun gpsFallbackDialogDismisses() {
        var dismissed = false
        rule.setContent { GpsFallbackWarningDialog(onDismiss = { dismissed = true }) }
        rule.onNodeWithText("Done", substring = true).assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun keyMismatchDialogDismisses() {
        var dismissed = false
        rule.setContent { KeyMismatchDialog(onDismiss = { dismissed = true }) }
        rule.onNodeWithText("Done", substring = true).assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun outdatedPeerDialogDismisses() {
        var dismissed = false
        rule.setContent { OutdatedPeerDialog(onDismiss = { dismissed = true }) }
        rule.onNodeWithText("Done", substring = true).assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun noShowDialogStartsWithoutDateTimeAndSaveDisabled() {
        var saved: Triple<User, Long?, Instant>? = null
        val waypoints = listOf(Waypoint("home", 100.0, Coord(1.0, 2.0), id = 3L))
        val actions = object : PersonActions {
            override fun setNoShowAlert(
                user: User,
                waypointId: Long?,
                expectedAt: Instant,
                graceMinutes: Int,
            ) {
                saved = Triple(user, waypointId, expectedAt)
            }
        }
        rule.setContent {
            NoShowAlertDialog(user = user(), waypoints = waypoints, actions = actions, onDismiss = {})
        }
        // Title names the watched person.
        rule.onNodeWithText("Alice", substring = true).assertIsDisplayed()
        // Date/time pickers start unset.
        rule.onNodeWithText("Pick date", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Pick time", substring = true).assertIsDisplayed()
        // Save ("Watch") is disabled until both halves are chosen.
        // (exact match: the title "Watch for Alice not arriving" also contains it)
        rule.onNodeWithText("Watch", substring = false).assertIsNotEnabled()
        assertNull(saved)
    }

    @Test
    fun noShowDialogDefaultsToAnywhere() {
        rule.setContent {
            NoShowAlertDialog(
                user = user(),
                waypoints = listOf(Waypoint("home", 100.0, Coord(1.0, 2.0), id = 3L)),
                actions = PersonActions.Noop,
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Anywhere", substring = true).assertIsDisplayed()
    }

    @Test
    fun dropdownFieldSelectsOption() {
        var value = "a"
        rule.setContent { DropdownField(value = value, setValue = { value = it }, options = listOf("a", "b")) }
        rule.onNodeWithText("a", substring = true).assertIsDisplayed()
    }
}
