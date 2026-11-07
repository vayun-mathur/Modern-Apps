package com.vayunmathur.health.platform

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for the medical save/delete operations ([MedicalClinicalOps.kt],
 * [MedicalMedicationOps.kt], [MedicalVaccinationOps.kt]): blank drafts save
 * nothing, filled drafts write Room rows and queue a mirror, deletes remove
 * the row.
 *
 * Two harnesses details matter. Each op launches into `viewModelScope`
 * (Robolectric's Main), whose Room round-trip finishes after the test thread
 * moves on, so [settle] pumps the main looper until the Room state lands
 * (virtual-time delays only — no wall-clock sleeps). And the screens' shared
 * `stateIn(WhileSubscribed)` flows hand a first collector their initial
 * empty list, so assertions read the repository's cold flows instead.
 *
 * Health Connect is unavailable under Robolectric, so the mirror half stays
 * queued — the assertions pin the Room write plus the pending-mirror
 * bookkeeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalOpsTest {

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

    private fun repository(): HealthRepository = HealthRepository.get(context)
    private fun viewModel(): MedicalViewModel = MedicalViewModel(context, repository())

    /** Pumps Main until [cond] holds; virtual time only, so failures fail fast without sleeping. */
    private suspend fun settle(cond: suspend () -> Boolean) {
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            delay(5)
        }
    }

    private val t = Instant.parse("2026-03-04T09:30:00Z")

    @Test
    fun `blank allergy draft saves nothing`() = runTest {
        val repo = repository()
        val before = repo.getAllergiesFlow().first().size
        val vm = viewModel()
        vm.startAllergyDraft()
        vm.saveAllergyDraft()
        settle { repo.getPendingMirrors().any { it.kind == MedicalKind.Allergy } }
        assertEquals(before, repo.getAllergiesFlow().first().size)
    }

    @Test
    fun `allergy draft saves trimmed entry and queues a mirror`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.startAllergyDraft()
        vm.editAllergyDraft {
            it.copy(
                displayName = "  Penicillin Ops  ",
                category = AllergyCategory.Medication,
                criticality = AllergyCriticality.High,
                reaction = "hives",
            )
        }
        vm.saveAllergyDraft()
        // Gate on the pending queue: the save coroutine writes the row, then
        // queues the mirror. Waiting for the queue means both halves landed.
        settle { repo.getPendingMirrors().any { it.kind == MedicalKind.Allergy } }
        val row = repo.getAllergiesFlow().first().first { it.displayName == "Penicillin Ops" }
        assertEquals(AllergyCriticality.High, row.criticality)
        assertEquals("hives", row.reaction)
    }

    @Test
    fun `numeric lab result parses with comma decimal`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.startLabDraft()
        vm.editLabDraft { it.copy(displayName = "Glucose Ops", value = "5,4", unit = "mmol/L") }
        vm.saveLabDraft()
        settle { repo.getLabResultsFlow().first().any { it.displayName == "Glucose Ops" } }
        val row = repo.getLabResultsFlow().first().first { it.displayName == "Glucose Ops" }
        assertEquals(5.4, row.value)
        assertEquals(null, row.valueText)
        assertEquals("mmol/L", row.unit)
    }

    @Test
    fun `text lab result is kept verbatim never coerced to zero`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.startLabDraft()
        vm.editLabDraft { it.copy(displayName = "COVID Ops", value = "positive") }
        vm.saveLabDraft()
        settle { repo.getLabResultsFlow().first().any { it.displayName == "COVID Ops" } }
        val row = repo.getLabResultsFlow().first().first { it.displayName == "COVID Ops" }
        assertEquals(null, row.value)
        assertEquals("positive", row.valueText)
    }

    @Test
    fun `blank condition draft saves nothing`() = runTest {
        val repo = repository()
        val before = repo.getConditionsFlow().first().size
        val vm = viewModel()
        vm.startConditionDraft()
        vm.saveConditionDraft()
        settle { repo.getPendingMirrors().any { it.kind == MedicalKind.Condition } }
        assertEquals(before, repo.getConditionsFlow().first().size)
    }

    @Test
    fun `condition draft saves with status`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.startConditionDraft()
        vm.editConditionDraft { it.copy(displayName = "Asthma Ops", status = ConditionStatus.Active) }
        vm.saveConditionDraft()
        settle { repo.getConditionsFlow().first().any { it.displayName == "Asthma Ops" } }
        val row = repo.getConditionsFlow().first().first { it.displayName == "Asthma Ops" }
        assertEquals(ConditionStatus.Active, row.status)
    }

    @Test
    fun `delete allergy removes the row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val entry = AllergyEntry(id = "a-del-ops", displayName = "Latex Ops", recordedAt = t)
        repo.upsertAllergy(entry)
        settle { repo.getAllergy("a-del-ops") != null }
        vm.deleteAllergy(entry)
        settle { repo.getAllergy("a-del-ops") == null }
        assertNull(repo.getAllergy("a-del-ops"))
    }

    @Test
    fun `delete condition removes the row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val entry = ConditionEntry(id = "c-del-ops", displayName = "Eczema Ops", onsetAt = t)
        repo.upsertCondition(entry)
        vm.deleteCondition(entry)
        settle { repo.getCondition("c-del-ops") == null }
        assertNull(repo.getCondition("c-del-ops"))
    }

    @Test
    fun `delete lab result removes the row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val entry = LabResultEntry(id = "l-del-ops", displayName = "A1c Ops", takenAt = t)
        repo.upsertLabResult(entry)
        vm.deleteLabResult(entry)
        settle { repo.getLabResult("l-del-ops") == null }
        assertNull(repo.getLabResult("l-del-ops"))
    }

    @Test
    fun `blank medication draft saves nothing`() = runTest {
        val repo = repository()
        val before = repo.getMedicationsFlow().first().size
        val vm = viewModel()
        vm.startMedicationDraft()
        vm.saveMedicationDraft()
        settle { repo.getPendingMirrors().any { it.kind == MedicalKind.Medication } }
        assertEquals(before, repo.getMedicationsFlow().first().size)
    }

    @Test
    fun `medication draft saves entry and schedule`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.startMedicationDraft()
        vm.editMedicationDraft {
            it.copy(ingredient = "Metformin Ops", dosage = "500 mg", remindersEnabled = true, times = listOf(9 * 3600))
        }
        vm.saveMedicationDraft()
        settle { repo.getPendingMirrors().any { it.kind == MedicalKind.Medication } }
        val row = repo.getMedicationsFlow().first().first { it.displayName == "Metformin Ops" }
        assertEquals("500 mg", row.dosageText)
        settle { repo.getScheduleFor(row.id) != null }
        val schedule = repo.getScheduleFor(row.id)
        assertNotNull(schedule)
        assertEquals(listOf(9 * 3600), schedule.times)
    }

    @Test
    fun `medication without reminders stores no schedule`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.startMedicationDraft()
        vm.editMedicationDraft { it.copy(ingredient = "Aspirin Ops", remindersEnabled = false) }
        vm.saveMedicationDraft()
        settle { repo.getMedicationsFlow().first().any { it.displayName == "Aspirin Ops" } }
        val row = repo.getMedicationsFlow().first().first { it.displayName == "Aspirin Ops" }
        assertNull(repo.getScheduleFor(row.id))
    }

    @Test
    fun `record dose taken writes a dose event`() = runTest {
        val repo = repository()
        val vm = viewModel()
        repo.upsertMedication(MedicationEntry(id = "med-dose-ops", displayName = "Lisinopril Ops", startedAt = t))
        vm.recordDoseTaken("med-dose-ops", t)
        settle { repo.getDosesFor("med-dose-ops").isNotEmpty() }
        assertEquals(1, repo.getDosesFor("med-dose-ops").size)
    }

    @Test
    fun `blank vaccination draft saves nothing`() = runTest {
        val repo = repository()
        val before = repo.getVaccinationsFlow().first().size
        val vm = viewModel()
        vm.startVaccinationDraft()
        vm.saveVaccinationDraft("attachment")
        settle { repo.getPendingMirrors().any { it.kind == MedicalKind.Vaccination } }
        assertEquals(before, repo.getVaccinationsFlow().first().size)
    }

    @Test
    fun `vaccination draft saves entry and queues a mirror`() = runTest {
        val repo = repository()
        val vm = viewModel()
        vm.startVaccinationDraft()
        vm.editVaccinationDraft { it.copy(displayName = "Flu Ops", lotNumber = "AB123") }
        vm.saveVaccinationDraft("attachment")
        settle { repo.getPendingMirrors().any { it.kind == MedicalKind.Vaccination } }
        val row = repo.getVaccinationsFlow().first().first { it.displayName == "Flu Ops" }
        assertEquals("AB123", row.lotNumber)
    }

    @Test
    fun `delete vaccination removes the row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val entry = VaccinationEntry(id = "v-del-ops", displayName = "COVID Ops", occurredAt = t)
        repo.upsertVaccination(entry)
        vm.deleteVaccination(entry)
        settle { repo.getVaccination("v-del-ops") == null }
        assertNull(repo.getVaccination("v-del-ops"))
    }

    @Test
    fun `formatNumber trims integral doubles for edit-back`() {
        assertEquals("12", formatNumber(12.0))
        assertEquals("5.4", formatNumber(5.4))
    }
}
