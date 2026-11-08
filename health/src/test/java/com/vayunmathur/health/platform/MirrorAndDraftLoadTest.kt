package com.vayunmathur.health.platform

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MirrorAndDraftLoadTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2024-04-02T08:00:00Z")

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

    private fun repo(): HealthRepository = HealthRepository.get(context)
    private fun vm(): MedicalViewModel = MedicalViewModel(context, repo())

    private fun settle() {
        repeat(50) { Shadows.shadowOf(Looper.getMainLooper()).idle() }
    }

    private suspend fun settleUntil(cond: () -> Boolean) {
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun `mirrors on missing rows clear their pending queue`() = runTest {
        val repo = repo()
        val vm = vm()
        listOf(MedicalKind.Allergy, MedicalKind.Condition, MedicalKind.LabResult, MedicalKind.Vaccination).forEach {
            repo.markPending(it, "missing-${it.name}")
        }
        vm.mirrorAllergy("missing-Allergy")
        vm.mirrorCondition("missing-Condition")
        vm.mirrorLabResult("missing-LabResult")
        vm.mirrorVaccination("missing-Vaccination")
        assertTrue(repo.getPendingMirrors().none { it.rowId.startsWith("missing-") })
    }

    @Test
    fun `mirrors on local rows stay queued while health connect unavailable`() = runTest {
        val repo = repo()
        val vm = vm()
        repo.upsertAllergy(AllergyEntry(id = "mirror-alg-1", displayName = "Mirror Peanut", recordedAt = at))
        repo.upsertCondition(ConditionEntry(id = "mirror-cond-1", displayName = "Mirror Asthma", onsetAt = at))
        repo.upsertLabResult(LabResultEntry(id = "mirror-lab-1", displayName = "Mirror A1c", value = 5.0, takenAt = at))
        repo.upsertVaccination(VaccinationEntry(id = "mirror-vac-1", displayName = "Mirror Flu", occurredAt = at))
        listOf(
            MedicalKind.Allergy to "mirror-alg-1", MedicalKind.Condition to "mirror-cond-1",
            MedicalKind.LabResult to "mirror-lab-1", MedicalKind.Vaccination to "mirror-vac-1",
        ).forEach { (kind, id) -> repo.markPending(kind, id) }
        vm.mirrorAllergy("mirror-alg-1")
        vm.mirrorCondition("mirror-cond-1")
        vm.mirrorLabResult("mirror-lab-1")
        vm.mirrorVaccination("mirror-vac-1")
        // No data source available: rows unchanged, queue retained for next sync.
        assertEquals("Mirror Peanut", repo.getAllergy("mirror-alg-1")?.displayName)
        assertTrue(repo.getPendingMirrors().any { it.rowId == "mirror-alg-1" })
    }

    @Test
    fun `disable reminders turns schedule off and missing schedule is no-op`() = runTest {
        val repo = repo()
        repo.upsertSchedule(MedicationSchedule(id = "mirror-sched-1", medicationId = "mirror-med-1", enabled = true, anchorDate = LocalDate(2024, 4, 2)))
        repo.disableReminders(context, "mirror-med-1")
        assertEquals(false, repo.getScheduleFor("mirror-med-1")?.enabled)
        repo.disableReminders(context, "no-such-med")
    }

    @Test
    fun `starting a medication draft loads entry and schedule`() = runTest {
        val repo = repo()
        repo.upsertMedication(MedicationEntry(id = "draft-med-load", displayName = "Loaded Ingredient", strength = "50mg", doseForm = "Capsule", startedAt = at))
        repo.upsertSchedule(MedicationSchedule(id = "draft-med-load", medicationId = "draft-med-load", enabled = true, times = listOf(28_800), anchorDate = LocalDate(2024, 4, 2)))
        val vm = vm()
        vm.startMedicationDraft("draft-med-load")
        settleUntil { vm.medicationDraft.value.editingId == "draft-med-load" }
        assertEquals("draft-med-load", vm.medicationDraft.value.editingId)
        assertEquals("Loaded Ingredient", vm.medicationDraft.value.ingredient)
        assertEquals("50mg", vm.medicationDraft.value.strength)
        assertTrue(vm.medicationDraft.value.remindersEnabled)
    }

    @Test
    fun `starting a vaccination draft loads entry`() = runTest {
        val repo = repo()
        repo.upsertVaccination(VaccinationEntry(id = "draft-vac-load", cvxCode = "141", displayName = "Loaded Flu", occurredAt = at, lotNumber = "LOTLOAD", site = "Arm", route = "IM", doseQuantity = "0.5", performer = "Clinic", note = "note"))
        val vm = vm()
        vm.startVaccinationDraft("draft-vac-load")
        settleUntil { vm.vaccinationDraft.value.editingId == "draft-vac-load" }
        assertEquals("draft-vac-load", vm.vaccinationDraft.value.editingId)
        assertEquals("Loaded Flu", vm.vaccinationDraft.value.displayName)
        assertEquals("LOTLOAD", vm.vaccinationDraft.value.lotNumber)
        assertEquals("141", vm.vaccinationDraft.value.cvxCode)
    }

    @Test
    fun `starting drafts for missing ids keeps blank draft`() {
        val vm = vm()
        vm.startMedicationDraft("no-such-med-draft")
        vm.startVaccinationDraft("no-such-vac-draft")
        vm.startAllergyDraft("no-such-alg-draft")
        vm.startConditionDraft("no-such-cond-draft")
        vm.startLabDraft("no-such-lab-draft")
        settle()
        assertEquals(null, vm.medicationDraft.value.editingId)
        assertEquals(null, vm.vaccinationDraft.value.editingId)
        assertNotNull(vm.allergyDraft.value)
    }
}
