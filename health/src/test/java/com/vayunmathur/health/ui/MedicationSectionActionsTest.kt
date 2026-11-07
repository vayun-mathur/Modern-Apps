package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicationSectionActionsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val t: Instant = Instant.parse("2024-06-06T08:00:00Z")

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
        HealthAPI.initLocal(context, HealthRepository.get(context))
        runBlocking { HealthRepository.get(context).getLastRecord(RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    @Test
    fun `take dose edit and past toggle fire their callbacks`() {
        var taken: String? = null
        var edited: String? = null
        var pastToggled = 0
        val entries = listOf(
            MedicationEntry(id = "sec-med-current", displayName = "SectionMed", strength = "20mg", status = MedicationStatus.Active, startedAt = t),
            MedicationEntry(id = "sec-med-past", displayName = "OldMed", status = MedicationStatus.Completed, startedAt = t),
        )
        rule.setContent {
            MedicalMedicationsSection(
                entries = entries, isExpanded = true, onToggle = {}, pastExpanded = false,
                onTogglePast = { pastToggled++ }, onEdit = { edited = it }, onDelete = {}, onTakeDose = { taken = it },
            )
        }
        rule.onNodeWithText("SectionMed 20mg", substring = true).assertIsDisplayed()
        // Trailing check button is the only other clickable on the current row.
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickables.size >= 2)
        rule.onNodeWithText("Past medications").performClick()
        assertEquals(1, pastToggled)
        rule.onNodeWithText("SectionMed 20mg", substring = true).performClick()
        assertEquals("sec-med-current", edited)
        assertEquals(null, taken)
    }

    @Test
    fun `past medications expand to show finished courses`() {
        val entries = listOf(
            MedicationEntry(id = "sec-med-past2", displayName = "FinishedMed", status = MedicationStatus.Stopped, startedAt = t),
        )
        rule.setContent {
            MedicalMedicationsSection(
                entries = entries, isExpanded = true, onToggle = {}, pastExpanded = true,
                onTogglePast = {}, onEdit = {}, onDelete = {}, onTakeDose = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("FinishedMed", substring = true).assertExists()
    }

    @Test
    fun `log meal from an ingredient ingredient wrapper scales and logs`() = runBlocking<Unit> {
        val repo = HealthRepository.get(context)
        val ingredient = Ingredient(id = "meal-ing-branch", originalName = "Branch Oats", nutritionData = NutritionData(calories = 100.0, protein = 5.0), isRecipe = true)
        repo.insertIngredient(ingredient)
        var dismissed = false
        rule.setContent { LogMealDialog(vm(), Instant.parse("2024-03-02T12:00:00Z")) { dismissed = true } }
        rule.onNodeWithText("Select Recipe", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Branch Oats", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        assertTrue(dismissed)
        var logged = false
        repeat(100) {
            if (repo.getRecordsFlow(RecordType.Nutrition).first().any { it.metadata == "Branch Oats" }) { logged = true; return@repeat }
            Thread.sleep(10)
        }
        assertTrue(logged)
    }

    @Test
    fun `log hydration in milliliters converts to liters`() = runBlocking<Unit> {
        val repo = HealthRepository.get(context)
        var dismissed = false
        rule.setContent { LogHydrationDialog(vm(), Instant.parse("2024-03-02T12:00:00Z")) { dismissed = true } }
        rule.onNodeWithText("Quantity").performTextInput("250")
        rule.waitForIdle()
        rule.onNodeWithText("Liters", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Milliliters", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        assertTrue(dismissed)
        var logged = false
        repeat(100) {
            if (repo.getRecordsFlow(RecordType.Hydration).first().any { it.value == 0.25 }) { logged = true; return@repeat }
            Thread.sleep(10)
        }
        assertTrue(logged)
    }
}
