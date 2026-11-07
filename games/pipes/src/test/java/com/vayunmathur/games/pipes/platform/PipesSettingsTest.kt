package com.vayunmathur.games.pipes.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.data.LevelPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings flows live in their own class so the shared DataStore singleton is first created
 * by this class's sandbox: a stale singleton from an earlier class can point at a deleted
 * Robolectric data directory and silently drop edits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PipesSettingsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        LevelPack.init(app)
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) { /* already initialised in this sandbox */ }
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun waitForValue(deadlineMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(25)
    }

    @Test fun settingsFlowsAndDismiss() {
        val vm = PipesViewModel(app)
        assertFalse(vm.colorblind.value)
        vm.setColorblind(true)
        waitForValue { vm.colorblind.value }
        assertTrue(vm.colorblind.value)
        vm.setColorblind(false)
        waitForValue { !vm.colorblind.value }
        assertFalse(vm.colorblind.value)
        // Reminder flows are WhileSubscribed, so observe them through a real collector
        kotlinx.coroutines.runBlocking {
            val minutes = async(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(20_000) { vm.reminderMinutesOfDay.first { it == 570L } }
            }
            vm.setReminderTime(9, 30)
            assertTrue(minutes.await() == 570L)
            val enabled = async(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(20_000) { vm.reminderEnabled.first { it } }
            }
            vm.setReminderEnabled(true)
            assertTrue(enabled.await())
            val disabled = async(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(20_000) { vm.reminderEnabled.first { !it } }
            }
            vm.setReminderEnabled(false)
            assertFalse(disabled.await())
        }
        vm.dismissAchievementNotification()
        assertNull(vm.achievementsManager.newAchievement.value)
        vm.achievementsManager.checkExistingAchievements()
    }
}
