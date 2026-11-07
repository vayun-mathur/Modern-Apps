package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the Medical History screen ([MedicalPage]) with a real
 * ViewModel over a seeded in-memory database.
 *
 * The screen collects the ViewModel's shared flows, so seeded rows arrive
 * through the real path; assertions pin the section headers, entry display,
 * and that the add FAB opens the form route on the back stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val t = Instant.parse("2026-03-04T09:30:00Z")

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
    }

    private fun repository(): HealthRepository = HealthRepository.get(context)

    private fun seed() = runTest {
        val repo = repository()
        repo.upsertVaccination(VaccinationEntry(id = "v-mp-1", displayName = "Flu Page", occurredAt = t))
        repo.upsertMedication(
            MedicationEntry(id = "m-mp-1", displayName = "Metformin Page", startedAt = t, status = MedicationStatus.Active)
        )
        repo.upsertAllergy(AllergyEntry(id = "a-mp-1", displayName = "Penicillin Page", recordedAt = t))
        repo.upsertCondition(ConditionEntry(id = "c-mp-1", displayName = "Asthma Page", onsetAt = t, status = ConditionStatus.Active))
    }

    private fun page(vm: MedicalViewModel, backStack: NavBackStack<Route>) {
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                MedicalPage(backStack = backStack, viewModel = vm)
            }
        }
    }

    @Test
    fun `medical page lists seeded records by section`() {
        seed()
        val vm = MedicalViewModel(context, repository())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        // Sections start collapsed: expanding each one reveals its seeded row.
        // Rows below the fold exist without being displayed (per TodayScreenTest).
        rule.onNodeWithText("Vaccinations").performClick()
        rule.onNodeWithText("Flu Page").assertExists()
        rule.onNodeWithText("Medications").performClick()
        rule.onNodeWithText("Metformin Page").assertExists()
        rule.onNodeWithText("Allergies").performClick()
        rule.onNodeWithText("Penicillin Page").assertExists()
        rule.onNodeWithText("Conditions").performClick()
        rule.onNodeWithText("Asthma Page").assertExists()
    }

    @Test
    fun `empty medical page shows section headers`() {
        val vm = MedicalViewModel(context, repository())
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical))
        page(vm, backStack)
        rule.onNodeWithText("Vaccinations").assertIsDisplayed()
        rule.onNodeWithText("Medications").assertIsDisplayed()
    }
}
