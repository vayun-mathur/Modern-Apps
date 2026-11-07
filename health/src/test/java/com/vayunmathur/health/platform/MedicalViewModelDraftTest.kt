package com.vayunmathur.health.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.VaccinationEntry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.vayunmathur.library.util.databases

/**
 * Tests for [MedicalViewModel]'s draft lifecycle: starting a blank form and
 * editing it, plus the derived flow mappings.
 *
 * The repository singleton is passed in but never touched by these paths:
 * a blank draft and an edit are pure state updates, and the flows below only
 * start collecting once subscribed. Anything reaching Room or Health Connect
 * belongs on the emulator.
 *
 * The singleton resolves its database through the shared cache, which would
 * otherwise build the SQLCipher file database and need AndroidKeyStore, so
 * each test points the cache at an in-memory instance first (seeding runs
 * inside the Robolectric sandbox, where the application context exists).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalViewModelDraftTest {

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
    }

    private fun viewModel(): MedicalViewModel =
        MedicalViewModel(context, HealthRepository.get(context))

    private val t = java.time.Instant.parse("2026-03-04T09:30:00Z")

    @Test
    fun `a fresh allergy draft is blank`() {
        val vm = viewModel()
        vm.startAllergyDraft()
        val draft = vm.allergyDraft.value
        assertNull(draft.editingId)
        assertEquals("", draft.displayName)
        assertEquals(AllergyCategory.Medication, draft.category)
        assertEquals(AllergyCriticality.Unknown, draft.criticality)
    }

    @Test
    fun `editing the allergy draft updates state`() {
        val vm = viewModel()
        vm.editAllergyDraft { it.copy(displayName = "Penicillin", criticality = AllergyCriticality.High) }
        assertEquals("Penicillin", vm.allergyDraft.value.displayName)
        assertEquals(AllergyCriticality.High, vm.allergyDraft.value.criticality)
    }

    @Test
    fun `a fresh condition draft is blank`() {
        val vm = viewModel()
        vm.startConditionDraft()
        assertNull(vm.conditionDraft.value.editingId)
        assertEquals(ConditionStatus.Active, vm.conditionDraft.value.status)
    }

    @Test
    fun `editing the condition draft updates state`() {
        val vm = viewModel()
        vm.editConditionDraft { it.copy(displayName = "Asthma", status = ConditionStatus.Resolved) }
        assertEquals("Asthma", vm.conditionDraft.value.displayName)
        assertEquals(ConditionStatus.Resolved, vm.conditionDraft.value.status)
    }

    @Test
    fun `a fresh vaccination draft is blank`() {
        val vm = viewModel()
        vm.startVaccinationDraft()
        assertNull(vm.vaccinationDraft.value.editingId)
        assertEquals("", vm.vaccinationDraft.value.displayName)
    }

    @Test
    fun `editing the vaccination draft updates state`() {
        val vm = viewModel()
        vm.editVaccinationDraft { it.copy(displayName = "Flu", lotNumber = "AB123") }
        assertEquals("Flu", vm.vaccinationDraft.value.displayName)
        assertEquals("AB123", vm.vaccinationDraft.value.lotNumber)
    }

    @Test
    fun `a fresh medication draft carries the 9am default`() {
        val vm = viewModel()
        vm.startMedicationDraft()
        assertNull(vm.medicationDraft.value.editingId)
        assertEquals(listOf(9 * 3600), vm.medicationDraft.value.times)
        assertEquals(MedicationStatus.Active, vm.medicationDraft.value.status)
    }

    @Test
    fun `editing the medication draft updates state`() {
        val vm = viewModel()
        vm.editMedicationDraft { it.copy(ingredient = "Metformin", remindersEnabled = true) }
        assertEquals("Metformin", vm.medicationDraft.value.ingredient)
        assertTrue(vm.medicationDraft.value.remindersEnabled)
    }

    @Test
    fun `a fresh lab draft is blank`() {
        val vm = viewModel()
        vm.startLabDraft()
        assertNull(vm.labDraft.value.editingId)
        assertEquals("", vm.labDraft.value.displayName)
    }

    @Test
    fun `editing the lab draft updates state`() {
        val vm = viewModel()
        vm.editLabDraft { it.copy(displayName = "A1c", value = "5.4") }
        assertEquals("A1c", vm.labDraft.value.displayName)
        assertEquals("5.4", vm.labDraft.value.value)
    }

    @Test
    fun `profile flow degrades to a blank profile before any write`() = runTest {
        val vm = viewModel()
        // No subscriber yet and no rows: the mapped default is the blank singleton.
        assertEquals("profile", vm.profile.value.id)
    }

    @Test
    fun `starting a draft for a missing row resets to blank`() {
        val vm = viewModel()
        vm.editAllergyDraft { it.copy(displayName = "stale") }
        vm.startAllergyDraft()
        assertEquals("", vm.allergyDraft.value.displayName)
    }
}
