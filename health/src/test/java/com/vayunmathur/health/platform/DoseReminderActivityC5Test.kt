package com.vayunmathur.health.platform

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowKeyguardManager
import org.robolectric.shadows.ShadowLooper

/**
 * The full-screen dose reminder activity: it loads the medication named
 * by its extras, and its buttons broadcast the same actions the
 * notification buttons use, then finish.
 */
/** Robolectric's keyguard shadow NPEs on a null dismiss callback. */
@Implements(android.app.KeyguardManager::class)
class C5ShadowKeyguardManager : ShadowKeyguardManager() {
    @Implementation
    override fun requestDismissKeyguard(activity: android.app.Activity, callback: android.app.KeyguardManager.KeyguardDismissCallback?) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [C5ShadowKeyguardManager::class])
class DoseReminderActivityC5Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<DoseReminderActivity>()

    // Runs before the compose rule launches the activity (rule order),
    // because the activity reads Room from its first composition.
    @get:Rule(order = 0)
    val seedRule = object : org.junit.rules.ExternalResource() {
        override fun before() {
            synchronized(databases) {
                if (databases[HealthDatabase::class] == null) {
                    databases[HealthDatabase::class] = Room
                        .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                        .allowMainThreadQueries()
                        .build()
                }
            }
            kotlinx.coroutines.runBlocking {
                HealthRepository.get(context).upsertMedication(
                    MedicationEntry(
                        id = "c5-med",
                        displayName = "C5 Aspirin",
                        strength = "100 MG",
                        doseForm = "Tablet",
                        startedAt = Instant.parse("2026-03-01T00:00:00Z"),
                    ),
                )
            }
        }
    }

    private fun settle() {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        rule.waitForIdle()
    }

    @Test
    fun reminderShellShowsBothActions() {
        settle()
        // Launched without extras: the name stays blank but both actions render.
        rule.onNodeWithText("Taken").assertIsDisplayed()
        rule.onNodeWithText("Snooze").assertIsDisplayed()
    }

    @Test
    fun takenButtonBroadcastsAndFinishes() {
        settle()
        val before = Shadows.shadowOf(context as Application).broadcastIntents.size
        rule.onNodeWithText("Taken").performClick()
        settle()
        val intents = Shadows.shadowOf(context as Application).broadcastIntents
        assertTrue(intents.size > before, "taken sends a broadcast")
        val broadcast = intents.last()
        assertTrue(
            broadcast.action == DoseActionReceiver.ACTION_TAKEN,
            "broadcast action is taken, was ${broadcast.action}",
        )
        assertTrue(rule.activity.isFinishing, "activity finishes after taken")
    }

    @Test
    fun snoozeButtonBroadcastsAndFinishes() {
        settle()
        rule.onNodeWithText("Snooze").performClick()
        settle()
        val intents = Shadows.shadowOf(context as Application).broadcastIntents
        val broadcast = intents.last()
        assertTrue(
            broadcast.action == DoseActionReceiver.ACTION_SNOOZE,
            "broadcast action is snooze, was ${broadcast.action}",
        )
        assertTrue(rule.activity.isFinishing, "activity finishes after snooze")
    }
}
