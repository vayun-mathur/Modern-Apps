package com.vayunmathur.health.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.health.platform.HealthConnectAvailability
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the dose reminder overlay, the Health Connect install
 * gate, and the offline-storage notice.
 *
 * Each is a small stateless screen with explicit callbacks, so the tests pin
 * exactly what the user sees and which callback each button reaches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderAndGateTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `reminder names the medication and offers taken and snooze`() {
        var taken = 0
        var snoozed = 0
        rule.setContent {
            DoseReminderScreen(
                medicationName = "Amoxicillin 500 mg",
                amount = "1 capsule",
                onTaken = { taken++ },
                onSnooze = { snoozed++ },
            )
        }
        rule.onNodeWithText("Time to take").assertIsDisplayed()
        rule.onNodeWithText("Amoxicillin 500 mg").assertIsDisplayed()
        rule.onNodeWithText("1 capsule").assertIsDisplayed()

        rule.onNodeWithText("Taken").performClick()
        assertTrue(taken >= 1, "onTaken fires")
        rule.onNodeWithText("Snooze").performClick()
        assertTrue(snoozed >= 1, "onSnooze fires")
    }

    @Test
    fun `reminder without an amount shows no amount line`() {
        rule.setContent {
            DoseReminderScreen(
                medicationName = "Metformin",
                amount = null,
                onTaken = {},
                onSnooze = {},
            )
        }
        rule.onNodeWithText("Metformin").assertIsDisplayed()
        rule.onNodeWithText("Taken").assertIsDisplayed()
        rule.onNodeWithText("Snooze").assertIsDisplayed()
    }

    @Test
    fun `install gate offers the store listing`() {
        var installs = 0
        rule.setContent {
            HealthConnectRequiredPage(
                availability = HealthConnectAvailability.NOT_INSTALLED,
                onInstall = { installs++; true },
            )
        }
        rule.onNodeWithText("Health Connect is required").assertIsDisplayed()
        rule.onNodeWithText("Install Health Connect").performClick()
        assertTrue(installs >= 1, "onInstall fires")
        // Success leaves no failure text behind.
        rule.onNodeWithText("Play Store", substring = true).assertDoesNotExist()
    }

    @Test
    fun `install gate keeps the failure on screen when nothing handles the store`() {
        rule.setContent {
            HealthConnectRequiredPage(
                availability = HealthConnectAvailability.NOT_INSTALLED,
                onInstall = { false },
            )
        }
        rule.onNodeWithText("Install Health Connect").performClick()
        rule.onNodeWithText("Play Store", substring = true).assertIsDisplayed()
    }

    @Test
    fun `update gate asks for an update instead of an install`() {
        rule.setContent {
            HealthConnectRequiredPage(
                availability = HealthConnectAvailability.UPDATE_REQUIRED,
                onInstall = { true },
            )
        }
        rule.onNodeWithText("Health Connect needs updating").assertIsDisplayed()
        rule.onNodeWithText("Health Connect is required").assertDoesNotExist()
    }

    @Test
    fun `storage notice explains app only storage`() {
        rule.setContent { MedicalStorageNotice() }
        rule.onNodeWithText("kept in the app only", substring = true).assertIsDisplayed()
    }
}
