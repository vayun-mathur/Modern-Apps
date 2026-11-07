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
    }

    @Test
    fun dashboardActivityShowsDashboardAndOpensEditors() {
        seedDatabase()
        ActivityScenario.launch(DashboardActivity::class.java).use { scenario ->
            composeRule.onNodeWithText("Parental controls").assertIsDisplayed()
            composeRule.onNodeWithText("Controls for this phone").assertIsDisplayed()
            var started: Intent? = null
            scenario.onActivity { started = shadowOf(it).nextStartedActivity }
            composeRule.onNodeWithText("Daily limit").performClick()
            scenario.onActivity { started = shadowOf(it).peekNextStartedActivity() }
            assertEquals(DailyLimitActivity::class.java.name, started?.component?.className)
            composeRule.onNodeWithText("App limits").performClick()
            scenario.onActivity { started = shadowOf(it).peekNextStartedActivity() }
            assertEquals(AppLimitsActivity::class.java.name, started?.component?.className)
        }
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
    fun pinSetupActivityRendersAndCancels() {
        ActivityScenario.launch(PinSetupActivity::class.java).use {
            composeRule.onNodeWithText("Set parent PIN").assertIsDisplayed()
            composeRule.onNodeWithText("New PIN").assertIsDisplayed()
            composeRule.onNodeWithText("Cancel").performClick()
            it.onActivity { activity -> assertTrue(activity.isFinishing) }
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
    fun pinGatedActivitiesShowContentAfterVerification() {
        seedDatabase()
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        com.vayunmathur.parentalcontrols.auth.ParentPin.get(
            ApplicationProvider.getApplicationContext(),
        ).set("1234")
        val cases = listOf(
            BedtimeActivity::class.java to "Child bedtime",
            AppLimitsActivity::class.java to "App limits",
            DailyLimitActivity::class.java to "Daily limit",
            DowntimeActivity::class.java to "Downtime",
            SchoolTimeActivity::class.java to "School time",
            WebContentFiltersActivity::class.java to "Web content filters",
        )
        for ((activity, title) in cases) {
            ActivityScenario.launch<android.app.Activity>(Intent(ApplicationProvider.getApplicationContext(), activity)).use { scenario ->
                var delivered = false
                scenario.onActivity { launched ->
                    val shadow = shadowOf(launched)
                    val forResult = shadow.nextStartedActivityForResult
                    if (forResult != null) {
                        shadow.receiveResult(forResult.intent, PinGateActivity.RESULT_VERIFIED, null)
                        delivered = true
                    }
                }
                assertTrue(delivered, "no gate launched for $activity")
                composeRule.onNodeWithText(title).assertIsDisplayed()
            }
        }
    }

    @Test
    fun pinGatedActivityFinishesWhenGateCancelled() {
        seedDatabase()
        com.vayunmathur.parentalcontrols.platform.FakeAndroidKeyStore.install()
        com.vayunmathur.parentalcontrols.auth.ParentPin.get(
            ApplicationProvider.getApplicationContext(),
        ).set("1234")
        ActivityScenario.launch(BedtimeActivity::class.java).use { scenario ->
            scenario.onActivity { launched ->
                val shadow = shadowOf(launched)
                val forResult = shadow.nextStartedActivityForResult
                shadow.receiveResult(forResult.intent, android.app.Activity.RESULT_CANCELED, null)
            }
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
