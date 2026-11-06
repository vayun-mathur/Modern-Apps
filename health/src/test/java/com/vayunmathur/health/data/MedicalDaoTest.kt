package com.vayunmathur.health.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO tests for the medical tables and the dose schedules: [MedicalDao] and
 * [ScheduleDao] against an in-memory [HealthDatabase].
 *
 * These pin the ordering contracts the medical pages rely on (reverse
 * chronology, most-critical-first allergies) and the schedule lookup paths
 * the reminder scheduler uses on boot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicalDaoTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: HealthDatabase = Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.medicalDao()
    private val schedules = db.scheduleDao()

    @AfterTest
    fun closeDb() {
        db.close()
    }

    private val t0 = java.time.Instant.parse("2026-01-01T00:00:00Z")
    private val t1 = java.time.Instant.parse("2026-02-01T00:00:00Z")

    // --- Vaccinations ------------------------------------------------------------

    @Test
    fun `vaccinations order newest first and round trip`() = runTest {
        val old = VaccinationEntry(id = "v-old", displayName = "Flu", occurredAt = t0)
        val new = VaccinationEntry(id = "v-new", displayName = "COVID", occurredAt = t1)
        dao.upsertVaccination(old)
        dao.upsertVaccination(new)
        assertEquals(listOf("v-new", "v-old"), dao.getVaccinationsFlow().first().map { it.id })
        assertEquals("Flu", dao.getVaccination("v-old")?.displayName)
        assertNull(dao.getVaccination("missing"))

        val mirrored = new.copy(fhirResourceId = "f1", dataSourceId = "hc")
        dao.upsertVaccination(mirrored)
        assertEquals("v-new", dao.findVaccination("hc", "f1")?.id)
        assertNull(dao.findVaccination("hc", "nope"))

        dao.deleteVaccination(mirrored)
        assertNull(dao.getVaccination("v-new"))
    }

    // --- Medications ---------------------------------------------------------------

    @Test
    fun `medications order newest first and find by fhir id`() = runTest {
        dao.upsertMedication(
            MedicationEntry(id = "m-old", displayName = "A", startedAt = t0)
        )
        dao.upsertMedication(
            MedicationEntry(
                id = "m-new", displayName = "B", startedAt = t1,
                fhirResourceId = "f9", dataSourceId = "hc",
            )
        )
        assertEquals(listOf("m-new", "m-old"), dao.getMedicationsFlow().first().map { it.id })
        assertEquals("m-new", dao.findMedication("hc", "f9")?.id)
        assertEquals(1, dao.findMedicationsByFhirId("f9").size)
        assertTrue(dao.findMedicationsByFhirId("nope").isEmpty())

        val updated = dao.getMedication("m-old")!!.copy(displayName = "A+")
        dao.updateMedication(updated)
        assertEquals("A+", dao.getMedication("m-old")?.displayName)

        dao.deleteMedication(updated)
        assertNull(dao.getMedication("m-old"))
    }

    // --- Attachments ---------------------------------------------------------------

    @Test
    fun `attachments order oldest first per vaccination`() = runTest {
        val a1 = MedicalAttachment(
            id = "a1", vaccinationId = "v1", fileName = "a.pdf", displayName = "A",
            mimeType = "application/pdf", sizeBytes = 10, importedAt = t0,
        )
        val a2 = MedicalAttachment(
            id = "a2", vaccinationId = "v1", fileName = "b.pdf", displayName = "B",
            mimeType = "application/pdf", sizeBytes = 20, importedAt = t1,
        )
        dao.insertAttachment(a2)
        dao.insertAttachment(a1)
        assertEquals(listOf("a1", "a2"), dao.getAttachmentsFlow().first().map { it.id })
        assertEquals(listOf("a1", "a2"), dao.getAttachmentsFor("v1").map { it.id })
        assertTrue(dao.getAttachmentsFor("other").isEmpty())
        dao.deleteAttachment(a1)
        assertEquals(listOf("a2"), dao.getAttachmentsFor("v1").map { it.id })
    }

    // --- Allergies -----------------------------------------------------------------

    @Test
    fun `allergies sort most critical first`() = runTest {
        dao.upsertAllergy(
            AllergyEntry(id = "mild", displayName = "Zebra", criticality = AllergyCriticality.Low, recordedAt = t0)
        )
        dao.upsertAllergy(
            AllergyEntry(
                id = "severe", displayName = "Apple", criticality = AllergyCriticality.High,
                recordedAt = t0,
            )
        )
        assertEquals(listOf("severe", "mild"), dao.getAllergiesFlow().first().map { it.id })
        assertNotNull(dao.getAllergy("severe"))
        assertNull(dao.getAllergy("missing"))

        val mirrored = AllergyEntry(
            id = "severe", displayName = "Apple", criticality = AllergyCriticality.High,
            recordedAt = t0, fhirResourceId = "fa", dataSourceId = "hc",
        )
        dao.upsertAllergy(mirrored)
        assertEquals("severe", dao.findAllergy("hc", "fa")?.id)

        dao.deleteAllergy(mirrored)
        assertNull(dao.getAllergy("severe"))
    }

    // --- Conditions and labs ---------------------------------------------------------

    @Test
    fun `conditions order newest onset first`() = runTest {
        dao.upsertCondition(ConditionEntry(id = "c-old", displayName = "Old", onsetAt = t0))
        dao.upsertCondition(
            ConditionEntry(
                id = "c-new", displayName = "New", onsetAt = t1,
                fhirResourceId = "fc", dataSourceId = "hc",
            )
        )
        assertEquals(listOf("c-new", "c-old"), dao.getConditionsFlow().first().map { it.id })
        assertEquals("c-new", dao.findCondition("hc", "fc")?.id)
        val entry = dao.getCondition("c-new")!!
        dao.deleteCondition(entry)
        assertNull(dao.getCondition("c-new"))
    }

    @Test
    fun `labs order newest first and flag range breaches`() = runTest {
        val inRange = LabResultEntry(
            id = "l-ok", displayName = "A1c", value = 5.4, unit = "%",
            referenceLow = 4.0, referenceHigh = 5.6, takenAt = t0,
        )
        val high = LabResultEntry(
            id = "l-high", displayName = "A1c", value = 9.0, unit = "%",
            referenceLow = 4.0, referenceHigh = 5.6, takenAt = t1,
            fhirResourceId = "fl", dataSourceId = "hc",
        )
        assertFalse(inRange.isOutOfRange)
        assertTrue(high.isOutOfRange)
        dao.upsertLabResult(inRange)
        dao.upsertLabResult(high)
        assertEquals(listOf("l-high", "l-ok"), dao.getLabResultsFlow().first().map { it.id })
        assertEquals("l-high", dao.findLabResult("hc", "fl")?.id)
        dao.deleteLabResult(high)
        assertNull(dao.getLabResult("l-high"))
    }

    // --- Profile ---------------------------------------------------------------------

    @Test
    fun `profile upserts and answers key by loinc`() = runTest {
        assertNull(dao.getProfile())
        dao.upsertProfile(HealthProfile(smokingStatus = SmokingStatus.Former))
        assertEquals(SmokingStatus.Former, dao.getProfile()?.smokingStatus)
        assertEquals(SmokingStatus.Former, dao.getProfileFlow().first()?.smokingStatus)

        val answer = ProfileAnswer(loincCode = "68518-0", answerCode = "LA6270-8", recordedAt = t0)
        dao.upsertProfileAnswer(answer)
        assertEquals("LA6270-8", dao.getProfileAnswer("68518-0")?.answerCode)
        assertEquals(1, dao.getProfileAnswersFlow().first().size)
        dao.deleteProfileAnswer("68518-0")
        assertNull(dao.getProfileAnswer("68518-0"))
    }

    // --- Dose events -------------------------------------------------------------------

    @Test
    fun `dose events order newest first and scope to medication`() = runTest {
        dao.upsertDoseEvent(DoseEvent(id = "d1", medicationId = "m1", takenAt = t0))
        dao.upsertDoseEvent(
            DoseEvent(
                id = "d2", medicationId = "m1", takenAt = t1,
                fhirResourceId = "fd", dataSourceId = "hc",
            )
        )
        dao.upsertDoseEvent(DoseEvent(id = "d3", medicationId = "m2", takenAt = t1))
        assertEquals(listOf("d2", "d3", "d1"), dao.getDoseEventsFlow().first().map { it.id })
        assertEquals(listOf("d2", "d1"), dao.getRecentDoses("m1", 5).map { it.id })
        assertEquals(listOf("d2"), dao.getRecentDoses("m1", 1).map { it.id })
        assertEquals("d2", dao.findDoseEvent("hc", "fd")?.id)
        assertEquals("d2", dao.getDoseEvent("d2")?.id)

        dao.deleteDosesFor("m1")
        assertTrue(dao.getDosesFor("m1").isEmpty())
        assertEquals(1, dao.getDosesFor("m2").size)

        val other = dao.getDoseEvent("d3")!!
        dao.deleteDoseEvent(other)
        assertNull(dao.getDoseEvent("d3"))
    }

    // --- Health Connect bookkeeping ------------------------------------------------------

    @Test
    fun `tombstones gate reimport and queue remote deletes`() = runTest {
        assertFalse(dao.isTombstoned(MedicalKind.Medication, "hc", "rx-1"))
        dao.upsertTombstone(
            DeletedMedicalResource(
                kind = MedicalKind.Medication, dataSourceId = "hc",
                fhirResourceId = "rx-1", fhirResourceType = 1, retryDelete = true,
            )
        )
        assertTrue(dao.isTombstoned(MedicalKind.Medication, "hc", "rx-1"))
        assertFalse(dao.isTombstoned(MedicalKind.Allergy, "hc", "rx-1"))
        assertEquals(1, dao.getPendingRemoteDeletes("hc").size)
        assertTrue(dao.getPendingRemoteDeletes("other").isEmpty())
    }

    @Test
    fun `pending mirrors queue unmirrored rows`() = runTest {
        assertTrue(dao.getPendingMirrors().isEmpty())
        dao.insertPendingMirror(PendingMirror(MedicalKind.Vaccination, "v1"))
        // Insert-or-ignore: no duplicates.
        dao.insertPendingMirror(PendingMirror(MedicalKind.Vaccination, "v1"))
        assertEquals(1, dao.getPendingMirrors().size)
        dao.deletePendingMirror(MedicalKind.Vaccination, "v1")
        assertTrue(dao.getPendingMirrors().isEmpty())
    }

    // --- Schedules -------------------------------------------------------------------------

    private fun schedule(id: String, enabled: Boolean = true) = MedicationSchedule(
        id = id,
        medicationId = "med-$id",
        enabled = enabled,
        times = listOf(8 * 3600),
        repeatUnit = RepeatUnit.Daily,
        interval = 1,
        anchorDate = LocalDate(2026, 3, 2),
    )

    @Test
    fun `schedules look up by medication and list enabled only`() = runTest {
        schedules.upsertSchedule(schedule("s1"))
        schedules.upsertSchedule(schedule("s2", enabled = false))
        assertEquals("s1", schedules.getScheduleFor("med-s1")?.id)
        assertNull(schedules.getScheduleFor("med-missing"))
        assertEquals("s1", schedules.getSchedule("s1")?.id)
        assertEquals(listOf("s1"), schedules.getEnabledSchedules().map { it.id })
        assertEquals(2, schedules.getSchedulesFlow().first().size)

        schedules.deleteScheduleFor("med-s1")
        assertNull(schedules.getScheduleFor("med-s1"))
        schedules.deleteSchedule(schedule("s2"))
        assertTrue(schedules.getSchedulesFlow().first().isEmpty())
    }
}
