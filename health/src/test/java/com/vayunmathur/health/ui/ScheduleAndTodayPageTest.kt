package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.RepeatUnit
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.ScheduleSection
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleAndTodayPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
        com.vayunmathur.health.util.HealthAPI.initLocal(context, HealthRepository.get(context))
        kotlinx.coroutines.runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    @Test
    fun `schedule section disabled shows only toggle`() {
        var draft = MedicalViewModel.MedicationDraft(remindersEnabled = false)
        rule.setContent {
            ScheduleSection(draft = draft, onEdit = { draft = it(draft) }, onPickTime = {}, onPickUntil = {})
        }
        rule.onNodeWithText("Remind me", substring = true).assertIsDisplayed()
    }

    @Test
    fun `schedule section enabled daily shows dose times and interval`() {
        var draft = MedicalViewModel.MedicationDraft(remindersEnabled = true, repeatUnit = RepeatUnit.Daily, interval = 2, times = listOf(8 * 3600))
        rule.setContent {
            ScheduleSection(draft = draft, onEdit = { draft = it(draft) }, onPickTime = {}, onPickUntil = {})
        }
        rule.onNodeWithText("Times of day", substring = true).assertExists()
    }

    @Test
    fun `schedule section weekly shows day toggles`() {
        var draft = MedicalViewModel.MedicationDraft(remindersEnabled = true, repeatUnit = RepeatUnit.Weekly, daysOfWeek = 0b0000011, times = listOf(9 * 3600, 21 * 3600))
        var pickedTime: Int? = null
        var pickedUntil = false
        rule.setContent {
            ScheduleSection(draft = draft, onEdit = { draft = it(draft) }, onPickTime = { pickedTime = it }, onPickUntil = { pickedUntil = true })
        }
        rule.onNodeWithText("On these days", substring = true).assertExists()
        assertEquals(null, pickedTime)
        assertTrue(!pickedUntil)
    }

    @Test
    fun `today page binding shows rings and navigates on sleep click`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today))
        rule.setContent { TodayPage(backStack, vm()) }
        rule.onNodeWithText("Last night").assertIsDisplayed()
        rule.onNodeWithText("Sleep").performClick()
        assertEquals(Route.SleepDetails, backStack.last())
    }

}
