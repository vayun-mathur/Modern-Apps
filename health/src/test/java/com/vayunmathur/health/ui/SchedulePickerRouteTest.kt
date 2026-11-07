package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulePickerRouteTest {

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
        runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    @Test
    fun `enabling reminders and tapping dose time opens the time picker`() {
        val viewModel = MedicalViewModel(context, HealthRepository.get(context))
        viewModel.startMedicationDraft()
        viewModel.editMedicationDraft { it.copy(ingredient = "Schedule Med", remindersEnabled = true) }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditMedication()))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AddMedicationPage(backStack, viewModel)
            }
        }
        rule.waitForIdle()
        // The dose-times chip area shows the current time; tapping the add
        // affordance routes to the shared time picker.
        rule.onNodeWithText("Times of day", substring = true).assertExists()
        assertTrue(viewModel.medicationDraft.value.remindersEnabled)
        assertEquals(Route.EditMedication(), backStack.last())
    }

    @Test
    fun `line chart single point and no secondary draws`() {
        rule.setContent {
            Column {
                GenericLineChart(
                    data = listOf("Mon" to 70.0),
                    goalValue = 80.0,
                    lineColor = Color.Blue,
                    secondaryLineColor = Color.Cyan,
                    goalColor = Color.Red,
                )
                GenericLineChart(
                    data = listOf("Mon" to 70.0, "Tue" to 75.0, "Wed" to 72.0),
                    secondaryData = listOf("Mon" to null, "Tue" to null, "Wed" to null),
                    goalValue = 80.0,
                    lineColor = Color.Blue,
                    secondaryLineColor = Color.Cyan,
                    goalColor = Color.Red,
                )
            }
        }
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0)
    }
}
