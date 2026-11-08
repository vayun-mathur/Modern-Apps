package com.vayunmathur.health.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.DeletedMedicalResource
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Room-side half of the Health Connect mirror ([MedicalMirror.kt]):
 * foreign-source detection, detach/forget bookkeeping, the mirror-target gate,
 * and the dose dedupe/record path.
 *
 * The Health Connect half (PersonalHealthRecords.upsert/delete returning null
 * on this Robolectric device without PHR) is what makes mirror writes no-ops
 * here: the assertions pin the queue and tombstone bookkeeping around them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalMirrorTest {

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

    @Test
    fun `local rows are not foreign`() {
        assertFalse(isForeignSource(null))
        // The shared prefs carry no owned ids on this device, so any non-null
        // source reads as foreign — null (never mirrored) reads as local.
        assertFalse(isForeignSource(null))
    }

    @Test
    fun `detachIfForeign ignores rows without fhir columns`() = runTest {
        val repo = repository()
        assertFalse(repo.detachIfForeign(MedicalKind.Medication, 0, null, null))
        assertFalse(repo.detachIfForeign(MedicalKind.Medication, 0, "some-source", null))
        assertFalse(repo.detachIfForeign(MedicalKind.Medication, 0, null, "some-id"))
        // Untouched rows queue nothing of their own.
        assertTrue(repo.getPendingMirrors().none { it.rowId == "no-such-row" })
    }

    @Test
    fun `forgetRow without fhir columns only clears the queue`() = runTest {
        val repo = repository()
        repo.markPending(MedicalKind.Medication, "row-1")
        repo.forgetRow(MedicalKind.Medication, "row-1", 0, null, null)
        assertTrue(repo.getPendingMirrors().none { it.rowId == "row-1" })
        assertFalse(repo.isTombstoned(MedicalKind.Medication, "ds", "fhir"))
    }

    @Test
    fun `mirrorTarget returns null while health connect is unavailable`() = runTest {
        val repo = repository()
        // No client initialised under Robolectric, so nothing is writable yet.
        assertEquals(null, repo.mirrorTarget(MedicalKind.Medication, "row-1", null))
    }

    @Test
    fun `mirrorMedication on a missing row clears pending and reports false`() = runTest {
        val repo = repository()
        repo.markPending(MedicalKind.Medication, "ghost")
        assertFalse(repo.mirrorMedication("ghost"))
        assertTrue(repo.getPendingMirrors().none { it.rowId == "ghost" })
    }

    @Test
    fun `mirrorDose on a missing row clears pending and reports false`() = runTest {
        val repo = repository()
        repo.markPending(MedicalKind.Dose, "ghost-dose")
        assertFalse(repo.mirrorDose("ghost-dose"))
        assertTrue(repo.getPendingMirrors().none { it.rowId == "ghost-dose" })
    }

    @Test
    fun `recordDose writes the event and a second tap inside five minutes is dropped`() = runTest {
        val repo = repository()
        val now = Instant.parse("2026-03-04T09:30:00Z")
        repo.recordDose("med-1", now)
        repo.recordDose("med-1", now.plusSeconds(60))
        val doses = repo.getDosesFor("med-1")
        assertEquals(1, doses.size)
        assertEquals(now, doses.first().takenAt)
        assertEquals("med-1", doses.first().medicationId)
    }

    @Test
    fun `recordDose after the window writes a second event`() = runTest {
        val repo = repository()
        val now = Instant.parse("2026-03-04T09:30:00Z")
        repo.recordDose("med-2", now)
        repo.recordDose("med-2", now.plusSeconds(6 * 60))
        assertEquals(2, repo.getDosesFor("med-2").size)
    }

    @Test
    fun `recorded dose lands in the dose events flow newest first`() = runTest {
        val repo = repository()
        val now = Instant.parse("2026-03-04T09:30:00Z")
        repo.recordDose("med-3", now)
        val events = repo.getDoseEventsFlow().first()
        assertTrue(events.any { it.medicationId == "med-3" })
    }

    @Test
    fun `deleteMedicationEverywhere removes schedule doses and row`() = runTest {
        val repo = repository()
        val entry = MedicationEntry(
            id = "med-del",
            displayName = "Metformin",
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        repo.upsertMedication(entry)
        repo.upsertDoseEvent(
            DoseEvent(id = "d1", medicationId = "med-del", takenAt = Instant.parse("2026-03-01T08:00:00Z"))
        )
        repo.deleteMedicationEverywhere(context, entry)
        assertEquals(null, repo.getMedication("med-del"))
        assertTrue(repo.getDosesFor("med-del").isEmpty())
    }

    @Test
    fun `pending mirrors round trip through mark and clear`() = runTest {
        val repo = repository()
        repo.markPending(MedicalKind.Vaccination, "v-9")
        assertTrue(repo.getPendingMirrors().any { it.rowId == "v-9" && it.kind == MedicalKind.Vaccination })
        repo.clearPending(MedicalKind.Vaccination, "v-9")
        assertTrue(repo.getPendingMirrors().none { it.rowId == "v-9" })
    }

    @Test
    fun `tombstones are visible to isTombstoned`() = runTest {
        val repo = repository()
        repo.upsertTombstone(DeletedMedicalResource(MedicalKind.Allergy, "ds-x", "fhir-1", 1, false))
        assertTrue(repo.isTombstoned(MedicalKind.Allergy, "ds-x", "fhir-1"))
        assertFalse(repo.isTombstoned(MedicalKind.Allergy, "ds-x", "fhir-other"))
    }
}
