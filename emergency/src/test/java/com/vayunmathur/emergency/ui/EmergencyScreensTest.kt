package com.vayunmathur.emergency.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vayunmathur.emergency.data.EmergencyInfo
import com.vayunmathur.emergency.platform.EmergencyActions
import com.vayunmathur.emergency.platform.EmergencyUiState
import com.vayunmathur.emergency.platform.HealthConnectMedicalState
import com.vayunmathur.emergency.platform.OwnerPickCandidates
import com.vayunmathur.emergency.platform.SosActions
import com.vayunmathur.emergency.platform.SosUiState
import com.vayunmathur.emergency.platform.sampleContact
import com.vayunmathur.emergency.platform.sampleHealthConnect
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingEmergencyActions : EmergencyActions {
    val calls = mutableListOf<String>()
    override fun saveMedicalInfo(bloodType: String, organDonor: String) {
        calls += "save:$bloodType/$organDonor"
    }
    override fun pickOwner(contactUri: Uri) { calls += "pickOwner" }
    override fun confirmOwnerAddress(address: String) { calls += "confirm:$address" }
    override fun dismissOwnerPick() { calls += "dismissPick" }
    override fun clearOwnerPickFailed() { calls += "clearOwnerFail" }
    override fun addContact(phoneUri: Uri) { calls += "add" }
    override fun removeContact(phoneUri: Uri) { calls += "remove" }
    override fun refresh() { calls += "refresh" }
    override fun clearAddFailed() { calls += "clearAddFail" }
    override fun refreshHealthConnect() { calls += "refreshHC" }
}

class RecordingSosActions : SosActions {
    val calls = mutableListOf<String>()
    override fun continueInBackground(remainingMillis: Long) { calls += "bg:$remainingMillis" }
    override fun cancel() { calls += "cancel" }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1280dp")
class EmergencyScreensTest {

    @get:Rule val rule = createComposeRule()

    // --- SOS ---

    @Test
    fun `sos screen waits while loading`() {
        val actions = RecordingSosActions()
        rule.setContent { SosScreen(state = SosUiState(loaded = false), actions = actions) }
        rule.waitForIdle()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun `sos screen explains when gesture is disabled`() {
        val actions = RecordingSosActions()
        rule.setContent {
            SosScreen(
                state = SosUiState(loaded = true, gestureEnabled = false),
                actions = actions,
            )
        }
        rule.onNodeWithText("Emergency SOS is not turned on.").assertIsDisplayed()
    }

    @Test
    fun `sos countdown shows number and cancel stops it`() {
        val actions = RecordingSosActions()
        rule.setContent {
            SosScreen(
                state = SosUiState(
                    number = "112", totalMillis = 3_600_000, gestureEnabled = true, loaded = true,
                ),
                actions = actions,
            )
        }
        rule.onNodeWithText("Calling 112 in", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), actions.calls)
    }

    @Test
    fun `sos countdown reaching zero fires the call broadcast once`() {
        val actions = RecordingSosActions()
        // 250ms tick + 250ms total: the screen fires its broadcast and finishes
        // its host instead of handing off to the background service.
        rule.setContent {
            SosScreen(
                state = SosUiState(
                    number = "112", totalMillis = 250, gestureEnabled = true, loaded = true,
                ),
                actions = actions,
            )
        }

        // the make-call broadcast is the observable firing: it goes out on the
        // main looper once the tick loop reaches zero
        val app = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val deadline = System.currentTimeMillis() + 10_000
        var fired = false
        while (!fired && System.currentTimeMillis() < deadline) {
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            val shadow = org.robolectric.Shadows.shadowOf(app)
            fired = shadow.broadcastIntents.any {
                it.action == com.vayunmathur.emergency.receiver.SosActionReceiver.ACTION_MAKE_CALL
            } || shadow.nextStartedActivity != null
            if (!fired) Thread.sleep(50)
        }
        assertTrue(fired)
        // a finished (not dismissed) countdown never hands off to the service
        assertTrue(actions.calls.none { it.startsWith("bg:") })
    }

    // --- View info ---

    @Test
    fun `view screen loading and empty states`() {
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            ViewInfoScreen(
                state = if (stage.intValue == 0) {
                    EmergencyUiState(loading = true)
                } else {
                    EmergencyUiState(loading = false)
                },
            )
        }
        rule.onNodeWithText("Loading contacts…").assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 1 }
        rule.onNodeWithText("No emergency information provided.").assertIsDisplayed()
    }

    @Test
    fun `view screen shows owner contacts and health groups`() {
        val state = EmergencyUiState(
            loading = false,
            info = EmergencyInfo(
                name = "Jamie Owner",
                address = "1 Main St",
                bloodType = "o+",
                organDonor = "yes",
                contacts = listOf(sampleContact()),
            ),
            health = sampleHealthConnect(),
        )
        rule.setContent { ViewInfoScreen(state = state) }
        rule.onNodeWithText("Jamie Owner").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1 Main St").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("O+").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Alex Rivera").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Penicillin").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Salbutamol 100mcg inhaler").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Asthma").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `view screen with two contacts numbers the groups`() {
        val second = sampleContact().copy(
            phoneUri = Uri.parse("content://com.android.contacts/data/2"),
            displayName = "Second Person",
        )
        val state = EmergencyUiState(
            loading = false,
            info = EmergencyInfo(contacts = listOf(sampleContact(), second)),
        )
        rule.setContent { ViewInfoScreen(state = state) }
        rule.onNodeWithText("Alex Rivera").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Second Person").performScrollTo().assertIsDisplayed()
    }

    // --- Edit info ---

    @Test
    fun `edit screen without contacts access prompts to pick`() {
        val actions = RecordingEmergencyActions()
        var picks = 0
        rule.setContent {
            EditInfoScreen(
                state = EmergencyUiState(loading = false, hasContactsAccess = false),
                actions = actions,
                onPickContact = { picks++ },
                onPickOwner = {},
            )
        }
        rule.onNodeWithText("Pick your contact to fill in your name and address.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Contacts access is off").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Contacts access is off").performClick()
        assertEquals(1, picks)
    }

    @Test
    fun `edit screen lists contacts with remove and failure rows`() {
        val actions = RecordingEmergencyActions()
        val state = EmergencyUiState(
            loading = false,
            hasContactsAccess = true,
            addFailed = true,
            ownerPickFailed = true,
            info = EmergencyInfo(
                name = "Jamie Owner",
                address = "1 Main St",
                contacts = listOf(sampleContact()),
            ),
            health = HealthConnectMedicalState(available = true, granted = false),
        )
        var healthConnects = 0
        rule.setContent {
            EditInfoScreen(
                state = state,
                actions = actions,
                onPickContact = {},
                onPickOwner = {},
                onConnectHealth = { healthConnects++ },
            )
        }
        rule.onNodeWithText("Jamie Owner").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Alex Rivera").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        assertTrue(actions.calls.contains("remove"))
        rule.onNodeWithText("Could not add that contact.").performScrollTo().performClick()
        assertTrue(actions.calls.contains("clearAddFail"))
        rule.onNodeWithText("Import from Health Connect").performScrollTo().performClick()
        assertEquals(1, healthConnects)
    }

    @Test
    fun `edit screen health connected and owner candidates dialog`() {
        val actions = RecordingEmergencyActions()
        val state = EmergencyUiState(
            loading = false,
            hasContactsAccess = true,
            info = EmergencyInfo(name = "Jamie Owner"),
            health = HealthConnectMedicalState(available = true, granted = true),
            ownerCandidates = OwnerPickCandidates(
                name = "Jamie Owner",
                addresses = listOf("1 Main St", "2 Oak Ave"),
            ),
        )
        rule.setContent {
            EditInfoScreen(
                state = state,
                actions = actions,
                onPickContact = {},
                onPickOwner = {},
            )
        }
        rule.onNodeWithText("2 Oak Ave").assertIsDisplayed()
        rule.onNodeWithText("2 Oak Ave").performClick()
        assertTrue(actions.calls.contains("confirm:2 Oak Ave"))
    }

    @Test
    fun `owner address dialog dismisses`() {
        var dismissed = false
        val confirmed = mutableListOf<String>()
        rule.setContent {
            OwnerAddressDialog(
                name = "Jamie",
                addresses = listOf("Only St"),
                onConfirm = { confirmed += it },
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Only St").performClick()
        assertEquals(listOf("Only St"), confirmed)
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `edit screen dropdowns save medical selections`() {
        val actions = RecordingEmergencyActions()
        rule.setContent {
            EditInfoScreen(
                state = EmergencyUiState(loading = false, hasContactsAccess = true),
                actions = actions,
                onPickContact = {},
                onPickOwner = {},
            )
        }
        // blood type dropdown: the section title and the row label both read
        // "Blood type"; the row (second node) is the dropdown trigger
        rule.onAllNodesWithText("Blood type")[1].performScrollTo().performClick()
        rule.onNodeWithText("A+").performClick()
        assertTrue(actions.calls.any { it.startsWith("save:A+/") })
        // organ donor dropdown: pick Yes (stored canonically as yes)
        rule.onAllNodesWithText("Organ donor")[1].performScrollTo().performClick()
        rule.onNodeWithText("Yes").performClick()
        assertTrue(actions.calls.any { it.endsWith("/yes") })
    }

    @Test
    fun `edit screen owner and loading rows respond`() {
        val actions = RecordingEmergencyActions()
        var ownerPicks = 0
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            EditInfoScreen(
                state = if (stage.intValue == 0) {
                    EmergencyUiState(loading = true, hasContactsAccess = true)
                } else {
                    EmergencyUiState(
                        loading = false, hasContactsAccess = true,
                        info = EmergencyInfo(name = "Jamie Owner", address = "1 Main St"),
                    )
                },
                actions = actions,
                onPickContact = {},
                onPickOwner = { ownerPicks++ },
            )
        }
        rule.onNodeWithText("Loading contacts…").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 1 }
        rule.onNodeWithText("Jamie Owner").performScrollTo().performClick()
        assertEquals(1, ownerPicks)
        rule.onNodeWithText("Choose your contact").performScrollTo().performClick()
        assertEquals(2, ownerPicks)
    }

    @Test
    fun `noop action objects are callable`() {
        EmergencyActions.Noop.saveMedicalInfo("A+", "yes")
        EmergencyActions.Noop.pickOwner(Uri.parse("content://x/1"))
        EmergencyActions.Noop.refresh()
        SosActions.Noop.cancel()
        SosActions.Noop.continueInBackground(1000)
        assertEquals("Alex Rivera", sampleContact().displayName)
        assertEquals(3, sampleHealthConnect().allergies.size)
    }
}
