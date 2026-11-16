package com.vayunmathur.parentalcontrols.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.parentalcontrols.data.ParentalControlsDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ParentalActivitiesTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    companion object {
        private var sharedDb: ParentalControlsDatabase? = null
    }

    private fun seedDatabase() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, ParentalControlsDatabase::class.java)
            .setSingleConnectionPool()
            .setSingleConnectionPool()
            .allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[ParentalControlsDatabase::class] = mem }
        // Force the database open: closing a prior activity scenario can drop an
        // in-memory database's tables, and the first query re-creates them.
        runCatching { kotlinx.coroutines.runBlocking { mem.appRuleDao().all() } }
    }

    @Test
    fun dashboardShowsDashboardAndOpensEditorsThroughGate() {
        seedDatabase()
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        com.vayunmathur.parentalcontrols.auth.ParentPin.get(
            ApplicationProvider.getApplicationContext(),
        ).set("1234")
        ActivityScenario.launch(com.vayunmathur.parentalcontrols.MainActivity::class.java).use { scenario ->
            composeRule.onNodeWithText("Parental controls").assertIsDisplayed()
            composeRule.onNodeWithText("Controls for this phone").assertIsDisplayed()
            composeRule.onNodeWithText("Daily limit").performClick()
            composeRule.onNodeWithText("Parent PIN").assertIsDisplayed()
            composeRule.onNodeWithText("PIN").performTextInput("1234")
            composeRule.onNodeWithText("Unlock").performClick()
            waitForText("Daily limit")
            composeRule.onNodeWithText("Daily limit").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("App limits").performClick()
            composeRule.onNodeWithText("PIN").performTextInput("1234")
            composeRule.onNodeWithText("Unlock").performClick()
            waitForText("App limits")
            composeRule.onNodeWithText("App limits").assertIsDisplayed()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Web content filters").performClick()
            composeRule.onNodeWithText("PIN").performTextInput("1234")
            composeRule.onNodeWithText("Unlock").performClick()
            waitForText("Web content filters")
            composeRule.onNodeWithText("Web content filters").assertIsDisplayed()
        }
    }

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching {
                composeRule.onNodeWithText(text).assertIsDisplayed(); true
            }.getOrDefault(false)
            if (found) return
            Thread.sleep(50)
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun pinGateActivityRendersAndCancels() {
        ActivityScenario.launch(PinGateActivity::class.java).use {
            composeRule.onNodeWithText("Parent PIN").assertIsDisplayed()
            composeRule.onNodeWithText("Enter the parent PIN to change these settings.").assertIsDisplayed()
            composeRule.onNodeWithText("Cancel").performClick()
            it.onActivity { activity -> assertTrue(activity.isFinishing) }
        }
    }

    @Test
    fun pinSetupRouteRendersAndCancelsToDashboard() {
        seedDatabase()
        ActivityScenario.launch<com.vayunmathur.parentalcontrols.MainActivity>(
            com.vayunmathur.parentalcontrols.MainActivity.pinSetupIntent(
                ApplicationProvider.getApplicationContext(),
            ),
        ).use {
            composeRule.onNodeWithText("Set parent PIN").assertIsDisplayed()
            composeRule.onNodeWithText("New PIN").assertIsDisplayed()
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.onNodeWithText("Parental controls").assertIsDisplayed()
        }
    }

    @Test
    fun lockActivitiesRenderTheirReasonAndClose() {
        val cases = listOf(
            BedtimeLockActivity::class.java to "Bedtime",
            DowntimeLockActivity::class.java to "Downtime",
            SchoolTimeLockActivity::class.java to "School time",
            DailyLimitLockActivity::class.java to "Daily limit reached",
            AppLimitLockActivity::class.java to "App limit reached",
        )
        for ((activity, title) in cases) {
            val intent = BaseLockActivity.intent(
                ApplicationProvider.getApplicationContext(),
                activity,
                "com.example.game",
            )
            ActivityScenario.launch<android.app.Activity>(intent).use {
                composeRule.onNodeWithText(title).assertIsDisplayed()
                composeRule.onNodeWithText("OK").performClick()
                it.onActivity { launched -> assertTrue(launched.isFinishing) }
            }
        }
    }

    @Test
    fun gatedRoutesShowContentAfterVerification() {
        seedDatabase()
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        com.vayunmathur.parentalcontrols.auth.ParentPin.get(
            ApplicationProvider.getApplicationContext(),
        ).set("1234")
        val cases = listOf(
            com.vayunmathur.parentalcontrols.MainActivity.ACTION_BEDTIME to "Child bedtime",
            com.vayunmathur.parentalcontrols.MainActivity.ACTION_APP_LIMITS to "App limits",
            com.vayunmathur.parentalcontrols.MainActivity.ACTION_DAILY_LIMIT to "Daily limit",
            com.vayunmathur.parentalcontrols.MainActivity.ACTION_DOWNTIME to "Downtime",
            com.vayunmathur.parentalcontrols.MainActivity.ACTION_SCHOOL_TIME to "School time",
        )
        for ((action, title) in cases) {
            val intent = Intent(action)
                .setClass(
                    ApplicationProvider.getApplicationContext(),
                    com.vayunmathur.parentalcontrols.MainActivity::class.java,
                )
            ActivityScenario.launch<android.app.Activity>(intent).use {
                composeRule.onNodeWithText("Parent PIN").assertIsDisplayed()
                composeRule.onNodeWithText("PIN").performTextInput("1234")
                composeRule.onNodeWithText("Unlock").performClick()
                waitForText(title)
                composeRule.onNodeWithText(title).assertIsDisplayed()
            }
        }
    }

    @Test
    fun gatedHostFinishesWhenGateCancelled() {
        seedDatabase()
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        com.vayunmathur.parentalcontrols.auth.ParentPin.get(
            ApplicationProvider.getApplicationContext(),
        ).set("1234")
        val intent = Intent(com.vayunmathur.parentalcontrols.MainActivity.ACTION_BEDTIME)
            .setClass(
                ApplicationProvider.getApplicationContext(),
                com.vayunmathur.parentalcontrols.MainActivity::class.java,
            )
        ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
            composeRule.onNodeWithText("Cancel").performClick()
            scenario.onActivity { launched -> assertTrue(launched.isFinishing) }
        }
    }

    @Test
    fun pinGateActivityVerifiesCorrectPinAndReturnsVerified() {
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        com.vayunmathur.parentalcontrols.auth.ParentPin.get(
            ApplicationProvider.getApplicationContext(),
        ).set("1234")
        ActivityScenario.launch(PinGateActivity::class.java).use { scenario ->
            composeRule.onNodeWithText("PIN").performTextInput("1234")
            composeRule.onNodeWithText("Unlock").performClick()
            // Verification is asynchronous; wait for the activity to finish with the result.
            val deadline = System.currentTimeMillis() + 10_000
            var resultCode = Int.MIN_VALUE
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { resultCode = shadowOf(it).resultCode }
                if (resultCode == PinGateActivity.RESULT_VERIFIED) break
                Thread.sleep(50)
            }
            assertEquals(PinGateActivity.RESULT_VERIFIED, resultCode)
        }
    }

    @Test
    fun pinGateActivityWrongPinShowsError() {
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        com.vayunmathur.parentalcontrols.auth.ParentPin.get(
            ApplicationProvider.getApplicationContext(),
        ).set("1234")
        ActivityScenario.launch(PinGateActivity::class.java).use {
            composeRule.onNodeWithText("PIN").performTextInput("0000")
            composeRule.onNodeWithText("Unlock").performClick()
            val deadline = System.currentTimeMillis() + 10_000
            var sawError = false
            while (System.currentTimeMillis() < deadline && !sawError) {
                sawError = runCatching {
                    composeRule.onNodeWithText("Wrong PIN. Try again.").assertIsDisplayed(); true
                }.getOrDefault(false)
                if (!sawError) Thread.sleep(50)
            }
            assertTrue(sawError)
        }
    }

    @Test
    fun setupActivityHandsOffAndFinishes() {
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        val controller = org.robolectric.Robolectric.buildActivity(SetupActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun lockActivityIntentCarriesPackage() {
        val intent = BaseLockActivity.intent(
            ApplicationProvider.getApplicationContext(),
            AppLimitLockActivity::class.java,
            "com.example.game",
        )
        assertEquals("com.example.game", intent.getStringExtra(BaseLockActivity.EXTRA_PACKAGE))
        assertEquals(15, BaseLockActivity.BONUS_MINUTES)
        val deviceWide = BaseLockActivity.intent(
            ApplicationProvider.getApplicationContext(),
            DailyLimitLockActivity::class.java,
            null,
        )
        assertEquals(null, deviceWide.getStringExtra(BaseLockActivity.EXTRA_PACKAGE))
    }
}
