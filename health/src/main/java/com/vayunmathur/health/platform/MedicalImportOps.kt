package com.vayunmathur.health.platform

import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.HealthProfile
import com.vayunmathur.health.data.MedicalKind
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.ProfileAnswer
import com.vayunmathur.health.domain.FhirObservations
import com.vayunmathur.health.domain.FhirRecords
import com.vayunmathur.health.domain.SocialHistoryQuestions
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Brings Room and Health Connect into step: our own unsent changes go out, then everything
 * readable comes in.
 *
 * Reading covers every data source, not only this app's, because that is what surfaces records a
 * hospital or pharmacy has synced in. Rows are matched on data source plus FHIR id, since FHIR
 * ids are only unique within one source. This app's own records are never overwritten by what it
 * reads back — Room is the source of truth for them — while a provider's are kept up to date.
 */
// Broad catch below is deliberate: provider-written FHIR is adversarial input — one
// unparseable resource must not fail the whole import, and the Health Connect client
// plus the JSON parsers throw undocumented RuntimeExceptions.
@Suppress("TooGenericExceptionCaught")
fun MedicalViewModel.importFromHealthConnect() {
    if (syncingState.value || !PersonalHealthRecords.isAvailable()) return
    viewModelScope.launch {
        syncingState.value = true
        try {
            val own = PersonalHealthRecords.dataSourceId()
            if (own != null) {
                retryRemoteDeletes(own)
                pushPending()
            }
            importVaccinesAndMeds(own)
            importClinical(own)
            importProfile()
        } catch (e: Exception) {
            Log.error("MedicalViewModel", "Import from Health Connect failed", e)
        } finally {
            syncingState.value = false
        }
    }
}

private suspend fun MedicalViewModel.retryRemoteDeletes(own: String) {
    repository.getPendingRemoteDeletes(own).forEach { tombstone ->
        if (PersonalHealthRecords.delete(own, tombstone.fhirResourceType, tombstone.fhirResourceId)) {
            repository.upsertTombstone(tombstone.copy(retryDelete = false))
        }
    }
}

private suspend fun MedicalViewModel.pushPending() {
    repository.getPendingMirrors().forEach { pending ->
        when (pending.kind) {
            MedicalKind.Medication -> repository.mirrorMedication(pending.rowId)
            MedicalKind.Dose -> repository.mirrorDose(pending.rowId)
            MedicalKind.Vaccination -> mirrorVaccination(pending.rowId)
            MedicalKind.Allergy -> mirrorAllergy(pending.rowId)
            MedicalKind.Condition -> mirrorCondition(pending.rowId)
            MedicalKind.LabResult -> mirrorLabResult(pending.rowId)
        }
    }
}

/**
 * The local row id for a resource imported from another app's data source.
 *
 * Derived rather than random so the same resource always maps to the same row, and never the
 * bare FHIR id, which two sources can both use.
 */
private fun importedRowId(dataSourceId: String, fhirId: String): String =
    UUID.nameUUIDFromBytes("$dataSourceId/$fhirId".toByteArray()).toString()

/**
 * Applies one category of Health Connect resources to Room.
 *
 * For each resource: skipped if the user deleted it here; skipped if it is this app's own and
 * already stored; the local row removed if the author has retracted it; otherwise inserted, or
 * for a provider's record, updated in place under its existing row id.
 */
@Suppress("LongParameterList")
private suspend fun <Row : Any, Parsed : Any> MedicalViewModel.reconcile(
    kind: MedicalKind,
    resources: List<PersonalHealthRecords.RawResource>,
    own: String?,
    find: suspend (dataSourceId: String, fhirId: String) -> Row?,
    getById: suspend (rowId: String) -> Row?,
    rowIdOf: (Row) -> String,
    parse: (PersonalHealthRecords.RawResource) -> Parsed?,
    retract: suspend (Row) -> Unit,
    store: suspend (incoming: Parsed, rowId: String, existing: Row?) -> Unit,
) {
    resources.forEach { resource ->
        reconcileOne(kind, resource, own, find, getById, rowIdOf, parse, retract, store)
    }
}

/** Applies one resource within [reconcile]; see its contract. */
@Suppress("LongParameterList")
private suspend fun <Row : Any, Parsed : Any> MedicalViewModel.reconcileOne(
    kind: MedicalKind,
    resource: PersonalHealthRecords.RawResource,
    own: String?,
    find: suspend (dataSourceId: String, fhirId: String) -> Row?,
    getById: suspend (rowId: String) -> Row?,
    rowIdOf: (Row) -> String,
    parse: (PersonalHealthRecords.RawResource) -> Parsed?,
    retract: suspend (Row) -> Unit,
    store: suspend (incoming: Parsed, rowId: String, existing: Row?) -> Unit,
) {
    val source = resource.dataSourceId
    val fhirId = FhirObservations.resourceId(resource.data) ?: return
    if (repository.isTombstoned(kind, source, fhirId)) return
    val isOwn = source == own || PersonalHealthRecords.isOwnDataSource(source)
    val existing = find(source, fhirId)
    if (existing != null && isOwn) return

    if (FhirRecords.isEnteredInError(resource.data)) {
        existing?.let { retract(it) }
        return
    }
    val incoming = parse(resource) ?: return
    val rowId = existing?.let(rowIdOf) ?: if (isOwn) fhirId else importedRowId(source, fhirId)
    // Earlier builds used the bare FHIR id as the row id, and an edit could since have moved
    // such a row into this app's data source, where find() no longer matches it.
    if (existing == null && (getById(rowId) != null || getById(fhirId) != null)) return
    store(incoming, rowId, existing)
}

private suspend fun MedicalViewModel.importVaccinesAndMeds(own: String?) {
    reconcile(
        kind = MedicalKind.Vaccination,
        resources = PersonalHealthRecords.readAll(PersonalHealthRecords.vaccinesType),
        own = own,
        find = repository::findVaccination,
        getById = repository::getVaccination,
        rowIdOf = { it.id },
        parse = { FhirRecords.parseImmunization(it.data, it.dataSourceId) },
        retract = { row ->
            repository.getAttachmentsFor(row.id).forEach { attachment ->
                AttachmentStore.delete(getApplication(), attachment.fileName)
                repository.deleteAttachment(attachment)
            }
            repository.deleteVaccination(row)
        },
        store = { incoming, rowId, _ -> repository.upsertVaccination(incoming.copy(id = rowId)) },
    )

    // Medications are MedicationRequests; a MedicationStatement in the same category is either a
    // dose taken against one of them, or a provider reporting a medication without a plan.
    val medResources = PersonalHealthRecords.readAll(PersonalHealthRecords.medicationsType)
    val (legacy, current) = medResources.partition { resource ->
        val isOwn = resource.dataSourceId == own || PersonalHealthRecords.isOwnDataSource(resource.dataSourceId)
        isOwn && FhirRecords.isUnplannedMedicationStatement(resource.data)
    }
    if (own != null) migrateLegacyStatements(legacy)

    reconcile(
        kind = MedicalKind.Medication,
        resources = current,
        own = own,
        find = repository::findMedication,
        getById = repository::getMedication,
        rowIdOf = { it.id },
        parse = { resource ->
            FhirRecords.parseMedicationRequest(resource.data, resource.dataSourceId)
                ?: FhirRecords.parseMedicationStatement(resource.data, resource.dataSourceId)
                    ?.let { it to null }
        },
        retract = { row -> repository.deleteMedicationEverywhere(getApplication(), row) },
        store = { incoming, rowId, existing -> storeMedication(incoming, rowId, existing) },
    )

    reconcile(
        kind = MedicalKind.Dose,
        resources = current,
        own = own,
        find = repository::findDoseEvent,
        getById = repository::getDoseEvent,
        rowIdOf = { it.id },
        parse = { FhirRecords.parseDoseEvent(it.data, it.dataSourceId) },
        retract = { row -> repository.deleteDoseEvent(row) },
        store = { incoming, rowId, existing ->
            val medicationId = existing?.medicationId
                ?: resolveMedication(incoming.dataSourceId, incoming.medicationId)
            if (medicationId != null) {
                repository.upsertDoseEvent(incoming.copy(id = rowId, medicationId = medicationId))
            }
        },
    )
}

private suspend fun MedicalViewModel.storeMedication(
    incoming: Pair<MedicationEntry, MedicationSchedule?>,
    rowId: String,
    existing: MedicationEntry?,
) {
    val (entry, schedule) = incoming
    repository.upsertMedication(entry.copy(id = rowId))
    if (existing == null) {
        // An imported schedule arrives disabled, so this cannot start alarms on its own.
        schedule?.let { repository.upsertSchedule(it.copy(id = rowId, medicationId = rowId)) }
    } else if (entry.status != MedicationStatus.Active) {
        // The provider has stopped it, so whatever reminders the user turned on should stop too.
        repository.disableReminders(getApplication(), rowId)
    }
}

/**
 * The local medication a dose's `basedOn` names.
 *
 * Looked up in the dose's own data source first, where a relative reference resolves. Failing
 * that, any source, but only when exactly one medication carries that id: a dose this app wrote
 * against a provider's request points across sources, and guessing between two would attach the
 * dose to the wrong medication.
 */
private suspend fun MedicalViewModel.resolveMedication(dataSourceId: String?, requestId: String): String? {
    dataSourceId?.let { repository.findMedication(it, requestId) }?.let { return it.id }
    return repository.findMedicationsByFhirId(requestId).singleOrNull()?.id
}

/**
 * Replaces regimens an earlier build wrote as `MedicationStatement`s with `MedicationRequest`s.
 *
 * The request is written before the statement is deleted, so a failure part way leaves a
 * duplicate for the next sync to finish rather than no record at all.
 */
private suspend fun MedicalViewModel.migrateLegacyStatements(
    legacy: List<PersonalHealthRecords.RawResource>,
) {
    legacy.forEach { resource ->
        val fhirId = FhirObservations.resourceId(resource.data) ?: return@forEach
        val local = repository.findMedication(resource.dataSourceId, fhirId)
        val rowId = when {
            local != null -> local.id
            repository.isTombstoned(MedicalKind.Medication, resource.dataSourceId, fhirId) -> null
            else -> FhirRecords.parseMedicationStatement(resource.data, resource.dataSourceId)
                ?.let { parsed ->
                    if (repository.getMedication(fhirId) != null) return@let null
                    repository.upsertMedication(parsed.copy(id = fhirId))
                    fhirId
                }
        }
        if (rowId != null) {
            repository.markPending(MedicalKind.Medication, rowId)
            if (!repository.mirrorMedication(rowId)) return@forEach
        }
        PersonalHealthRecords.delete(
            resource.dataSourceId,
            PersonalHealthRecords.medicationStatementResourceType,
            fhirId,
        )
    }
}

private suspend fun MedicalViewModel.importClinical(own: String?) {
    reconcile(
        kind = MedicalKind.Allergy,
        resources = PersonalHealthRecords.readAll(PersonalHealthRecords.allergiesType),
        own = own,
        find = repository::findAllergy,
        getById = repository::getAllergy,
        rowIdOf = { it.id },
        parse = { FhirRecords.parseAllergyIntolerance(it.data, it.dataSourceId) },
        retract = { row -> repository.deleteAllergy(row) },
        store = { incoming, rowId, _ -> repository.upsertAllergy(incoming.copy(id = rowId)) },
    )

    reconcile(
        kind = MedicalKind.Condition,
        resources = PersonalHealthRecords.readAll(PersonalHealthRecords.conditionsType),
        own = own,
        find = repository::findCondition,
        getById = repository::getCondition,
        rowIdOf = { it.id },
        parse = { FhirRecords.parseCondition(it.data, it.dataSourceId) },
        retract = { row -> repository.deleteCondition(row) },
        store = { incoming, rowId, _ -> repository.upsertCondition(incoming.copy(id = rowId)) },
    )

    reconcile(
        kind = MedicalKind.LabResult,
        resources = PersonalHealthRecords.readAll(PersonalHealthRecords.labsType),
        own = own,
        find = repository::findLabResult,
        getById = repository::getLabResult,
        rowIdOf = { it.id },
        parse = { FhirRecords.parseLabResult(it.data, it.dataSourceId) },
        retract = { row -> repository.deleteLabResult(row) },
        store = { incoming, rowId, _ -> repository.upsertLabResult(incoming.copy(id = rowId)) },
    )
}

/**
 * Pulls the two standing observations back out of Health Connect.
 *
 * Both live in the same PHR category and are told apart by their LOINC code, since a category
 * read returns every social-history observation a provider has ever written — alcohol use,
 * housing status and the rest — not just the two this app writes. Only the newest of each is
 * kept: these are current answers, not a log.
 */
private suspend fun MedicalViewModel.importProfile() {
    val resources = PersonalHealthRecords.readAll(PersonalHealthRecords.socialHistoryType) +
        PersonalHealthRecords.readAll(PersonalHealthRecords.pregnancyType)
    if (resources.isEmpty()) return

    var profile = repository.getProfile() ?: HealthProfile()

    // Newest answer per question. A category read returns every social-history observation the
    // phone holds, which can include several for the same question - from a provider, or from
    // an older build of this app - and picking whichever came last in the list is how an answer
    // appears to revert after being set.
    val newest = mutableMapOf<String, ProfileAnswer>()

    resources.forEach { resource ->
        val loinc = FhirObservations.observationLoincCode(resource.data)
        when (loinc) {
            FhirRecords.LOINC_PREGNANCY_STATUS -> applyPregnancy(resource, profile).also { profile = it }
            FhirRecords.LOINC_SMOKING_STATUS -> applySmoking(resource, profile).also { profile = it }
            else -> applySocialHistoryAnswer(resource, loinc, newest)
        }
    }

    newest.values.forEach { incoming ->
        val local = repository.getProfileAnswer(incoming.loincCode)
        // Never overwrite a newer local answer. The user may have just tapped one while this
        // import was still reading, and their answer should win over what was on disk before.
        if (local == null || incoming.recordedAt.isAfter(local.recordedAt)) {
            repository.upsertProfileAnswer(incoming)
        }
    }
    repository.upsertProfile(profile)
}

private fun MedicalViewModel.applyPregnancy(
    resource: PersonalHealthRecords.RawResource,
    profile: HealthProfile,
): HealthProfile =
    FhirRecords.parsePregnancyStatus(resource.data)?.let { (status, due) ->
        profile.copy(
            pregnancyStatus = status,
            dueDate = due,
            pregnancyFhirId = profile.pregnancyFhirId
                ?: FhirObservations.resourceId(resource.data),
        )
    } ?: profile

private fun MedicalViewModel.applySmoking(
    resource: PersonalHealthRecords.RawResource,
    profile: HealthProfile,
): HealthProfile =
    FhirRecords.parseSmokingStatus(resource.data)?.let { status ->
        profile.copy(
            smokingStatus = status,
            smokingFhirId = profile.smokingFhirId
                ?: FhirObservations.resourceId(resource.data),
        )
    } ?: profile

private fun MedicalViewModel.applySocialHistoryAnswer(
    resource: PersonalHealthRecords.RawResource,
    loinc: String?,
    newest: MutableMap<String, ProfileAnswer>,
) {
    // Anything else in this category that is one of ours, matched by question code.
    // A provider's social history contains plenty this app does not ask about.
    val question = loinc?.let { SocialHistoryQuestions.byLoinc(it) } ?: return
    val answerCode = FhirObservations.parseSocialHistoryAnswer(resource.data) ?: return
    // Refuse an answer outside the question's own list rather than storing a code
    // the selector could never show.
    if (question.answers.none { it.code == answerCode }) return

    val candidate = ProfileAnswer(
        loincCode = question.loinc,
        answerCode = answerCode,
        // The observation's own date, not now. Using now would make every import
        // look like the freshest answer and would misdate the "Recorded" line.
        recordedAt = FhirObservations.observationEffective(resource.data) ?: return,
        fhirResourceId = FhirObservations.resourceId(resource.data),
        dataSourceId = resource.dataSourceId,
    )
    val held = newest[question.loinc]
    if (held == null || candidate.recordedAt.isAfter(held.recordedAt)) {
        newest[question.loinc] = candidate
    }
}
