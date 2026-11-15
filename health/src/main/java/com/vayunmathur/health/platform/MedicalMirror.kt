@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.platform

import android.content.Context
import com.vayunmathur.health.data.DeletedMedicalResource
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.domain.FhirRecords
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

/*
 * Keeps Health Connect in step with Room for the medical tables, from the ViewModel and from
 * receivers alike.
 *
 * Every local write queues a PendingMirror and every successful mirror clears it, so anything that
 * could not be written — Health Connect unavailable, a rejected resource, an alarm that started a
 * process with no client — is retried by the next sync. Deletes leave a DeletedMedicalResource so
 * the next import cannot bring the record back.
 *
 * Health Connect only lets an app write to its own data source, so a provider's record is never
 * rewritten in place: editing one detaches the local row into this app's own record and tombstones
 * the provider's original.
 */

/** Whether [dataSourceId] belongs to another app, such as a provider or a pharmacy. */
internal fun isForeignSource(dataSourceId: String?): Boolean =
    dataSourceId != null && !PersonalHealthRecords.isOwnDataSource(dataSourceId)

/**
 * Detaches a provider's record before the user's edit is saved over it.
 *
 * Returns true when the row was foreign. The provider's original is tombstoned so imports stop
 * overwriting the edit, and the caller clears the row's FHIR columns so the next mirror writes it
 * into this app's data source as the user's own version.
 */
internal suspend fun HealthRepository.detachIfForeign(
    kind: MedicalKind,
    fhirResourceType: Int,
    dataSourceId: String?,
    fhirResourceId: String?,
): Boolean {
    if (dataSourceId == null || fhirResourceId == null || !isForeignSource(dataSourceId)) return false
    upsertTombstone(
        DeletedMedicalResource(kind, dataSourceId, fhirResourceId, fhirResourceType, retryDelete = false)
    )
    return true
}

/**
 * Forgets a row that has just been deleted locally, in Health Connect and in the bookkeeping.
 *
 * Always tombstoned: a provider's copy cannot be deleted by this app, and deleting our own can
 * fail. Our own copy is flagged for retry until a delete lands.
 */
internal suspend fun HealthRepository.forgetRow(
    kind: MedicalKind,
    rowId: String,
    fhirResourceType: Int,
    dataSourceId: String?,
    fhirResourceId: String?,
) {
    clearPending(kind, rowId)
    if (dataSourceId == null || fhirResourceId == null) return
    val settled = isForeignSource(dataSourceId) ||
        PersonalHealthRecords.delete(dataSourceId, fhirResourceType, fhirResourceId)
    upsertTombstone(
        DeletedMedicalResource(kind, dataSourceId, fhirResourceId, fhirResourceType, retryDelete = !settled)
    )
}

/**
 * The data source to mirror a row into, or null when it should not be written now.
 *
 * Null when Health Connect is unavailable, in which case the row stays queued, or when the row is
 * a provider's and so not this app's to write, in which case the queue entry is dropped.
 */
internal suspend fun HealthRepository.mirrorTarget(
    kind: MedicalKind,
    rowId: String,
    rowDataSourceId: String?,
): String? {
    if (isForeignSource(rowDataSourceId)) {
        clearPending(kind, rowId)
        return null
    }
    return PersonalHealthRecords.dataSourceId()
}

/**
 * Writes the medication to Health Connect as a `MedicationRequest`.
 *
 * The schedule goes with it, which is what puts the reminder times in the record as
 * `dosageInstruction.timing.repeat` rather than leaving them as a private detail of this app.
 */
internal suspend fun HealthRepository.mirrorMedication(id: String): Boolean {
    val entry = getMedication(id) ?: run {
        clearPending(MedicalKind.Medication, id)
        return false
    }
    val target = mirrorTarget(MedicalKind.Medication, id, entry.dataSourceId) ?: return false
    val written = PersonalHealthRecords.upsert(
        target,
        FhirRecords.medicationRequestJson(entry, getScheduleFor(id)),
    ) ?: return false
    upsertMedication(entry.copy(fhirResourceId = written, dataSourceId = target))
    clearPending(MedicalKind.Medication, id)
    return true
}

/** Writes a dose as a `MedicationStatement`, mirroring its medication first if that never landed. */

/** The dose's medication, mirrored first when it never landed in Health Connect. */
internal suspend fun HealthRepository.mirroredMedication(event: DoseEvent): MedicationEntry? {
    val unmirrored = getMedication(event.medicationId) ?: return null
    if (unmirrored.fhirResourceId == null && !mirrorMedication(unmirrored.id)) return null
    return getMedication(event.medicationId)
}

internal suspend fun HealthRepository.mirrorDose(id: String): Boolean {
    val event = getDoseEvent(id) ?: run {
        clearPending(MedicalKind.Dose, id)
        return false
    }
    val target = mirrorTarget(MedicalKind.Dose, id, event.dataSourceId) ?: return false
    // basedOn must point at a MedicationRequest that actually exists in Health Connect.
    val medication = mirroredMedication(event) ?: return false

    val written = PersonalHealthRecords.upsert(
        target,
        FhirRecords.doseEventJson(event, medication),
    ) ?: return false
    upsertDoseEvent(event.copy(fhirResourceId = written, dataSourceId = target))
    clearPending(MedicalKind.Dose, id)
    return true
}

/** Two taps this close together are one dose recorded twice, from the reminder and the list. */
private val DUPLICATE_DOSE_WINDOW: Duration = Duration.ofMinutes(5)

/**
 * Records that a dose was taken: locally first, then mirrored.
 *
 * Only acknowledged doses are ever written — see [DoseEvent].
 */
internal suspend fun HealthRepository.recordDose(medicationId: String, takenAt: Instant = Instant.now()) {
    val last = getRecentDoses(medicationId, 1).firstOrNull()
    if (last != null && Duration.between(last.takenAt, takenAt).abs() < DUPLICATE_DOSE_WINDOW) return

    val event = DoseEvent(id = Uuid.random().toString(), medicationId = medicationId, takenAt = takenAt)
    upsertDoseEvent(event)
    markPending(MedicalKind.Dose, event.id)
    mirrorDose(event.id)
}

/**
 * Deletes a medication with its schedule and doses, here and in Health Connect.
 *
 * The doses go with it. Leaving them would strand statements pointing at a MedicationRequest that
 * no longer exists, and the next import would read them back as orphan rows.
 */
internal suspend fun HealthRepository.deleteMedicationEverywhere(context: Context, entry: MedicationEntry) {
    getScheduleFor(entry.id)?.let { DoseScheduler.cancel(context, it.id) }
    deleteScheduleFor(entry.id)
    val doses = getDosesFor(entry.id)
    deleteDosesFor(entry.id)
    deleteMedication(entry)
    doses.forEach { dose ->
        forgetRow(
            MedicalKind.Dose,
            dose.id,
            PersonalHealthRecords.medicationStatementResourceType,
            dose.dataSourceId,
            dose.fhirResourceId,
        )
    }
    forgetRow(
        MedicalKind.Medication,
        entry.id,
        PersonalHealthRecords.medicationRequestResourceType,
        entry.dataSourceId,
        entry.fhirResourceId,
    )
}

/** Turns a medication's reminders off without discarding the schedule itself. */
internal suspend fun HealthRepository.disableReminders(context: Context, medicationId: String) {
    val schedule = getScheduleFor(medicationId) ?: return
    DoseScheduler.cancel(context, schedule.id)
    if (schedule.enabled) upsertSchedule(schedule.copy(enabled = false))
}
