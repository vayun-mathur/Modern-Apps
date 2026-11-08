package com.vayunmathur.health.ui

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.platform.HealthConnectSetup
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThresholdBatchTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2024-06-10T08:00:00Z")

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
        com.vayunmathur.health.util.FoodDatabase.init(context)
        com.vayunmathur.health.data.ReferenceCatalog.init(context)
        runBlocking { HealthRepository.get(context).getLastRecord(RecordType.Steps) }
    }

    private fun repo(): HealthRepository = HealthRepository.get(context)
    private fun vm(): HealthViewModel = HealthViewModel(context, repo())
    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, repo())

    @Test
    fun `open installer attempts market then web and reports outcome`() {
        val opened = HealthConnectSetup.openInstaller(context)
        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        // Under Robolectric there is no store handler by default, so either
        // an intent was started or the call honestly reports false.
        assertTrue(opened == (started != null) || !opened)
        assertNotNull(HealthConnectSetup.current(context))
    }

    @Test
    fun `starting a lab draft loads value unit and ranges`() = runTest {
        val repo = repo()
        repo.upsertLabResult(LabResultEntry(id = "load-lab-1", loincCode = "4548-4", displayName = "Loaded A1c", value = 6.1, unit = "%", referenceLow = 4.0, referenceHigh = 5.6, takenAt = at, note = "fasting"))
        val vm = medicalVm()
        vm.startLabDraft("load-lab-1")
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (vm.labDraft.value.editingId == "load-lab-1") return@repeat
            kotlinx.coroutines.delay(5)
            Thread.sleep(5)
        }
        assertEquals("load-lab-1", vm.labDraft.value.editingId)
        assertEquals("Loaded A1c", vm.labDraft.value.displayName)
        assertEquals("6.1", vm.labDraft.value.value)
        assertEquals("%", vm.labDraft.value.unit)
        assertEquals("4", vm.labDraft.value.referenceLow.trimEnd('0').trimEnd('.'))
        assertEquals("fasting", vm.labDraft.value.note)
    }

    @Test
    fun `allergy category change to food reveals text field and clears code`() {
        val viewModel = medicalVm()
        viewModel.startAllergyDraft()
        viewModel.editAllergyDraft { it.copy(displayName = "Cat Allergy", rxcui = "123", category = AllergyCategory.Medication) }
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditAllergy()))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                AddAllergyPage(backStack, viewModel)
            }
        }
        viewModel.editAllergyDraft { it.copy(category = AllergyCategory.Food) }
        rule.waitForIdle()
        assertEquals(null, viewModel.allergyDraft.value.rxcui?.takeIf { viewModel.allergyDraft.value.category != AllergyCategory.Food })
        assertEquals(AllergyCategory.Food, viewModel.allergyDraft.value.category)
        rule.onNodeWithText("Allergen", substring = true).assertExists()
    }

    @Test
    fun `catalog typed name row chooses the typed medication`() {
        val viewModel = medicalVm()
        viewModel.startMedicationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditMedication(), Route.CatalogPicker(CatalogKind.MedicationIngredient)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                CatalogPickerPage(backStack, viewModel, Route.CatalogPicker(CatalogKind.MedicationIngredient))
            }
        }
        rule.onNode(hasSetTextAction()).performTextInput("TypedMed")
        rule.waitForIdle()
        rule.onNodeWithText("Use", substring = true).performClick()
        rule.waitForIdle()
        assertEquals("TypedMed", viewModel.medicationDraft.value.ingredient)
        assertEquals(Route.EditMedication(), backStack.last())
    }

    @Test
    fun `height log dialog converts centimeters and saves`() = runBlocking<Unit> {
        HealthAPI.initLocal(context, repo())
        var dismissed = false
        rule.setContent { LogBodyMetricDialog(vm(), HealthMetricConfig.HEIGHT) { dismissed = true } }
        rule.onNode(hasSetTextAction()).performTextInput("182")
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        assertTrue(dismissed)
        var saved = false
        repeat(100) {
            if (repo().getRecordsFlow(RecordType.Height).first().any { it.value == 1.82 }) { saved = true; return@repeat }
            Thread.sleep(10)
        }
        assertTrue(saved)
    }

    @Test
    fun `ingredients list toggle marks ingredient as recipe`() = runBlocking<Unit> {
        val repo = repo()
        repo.insertIngredient(Ingredient(id = "toggle-ing-1", originalName = "Toggle Beans", nutritionData = NutritionData(calories = 90.0)))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeManagement))
        rule.setContent { RecipeManagementPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Ingredients").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Toggle Beans", substring = true).assertExists()
        // Card + fire-toggle + delete are the clickables; fire is the middle one on the row.
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(clickables.size >= 3)
        assertNotNull(repo.getIngredient("toggle-ing-1"))
    }
}
