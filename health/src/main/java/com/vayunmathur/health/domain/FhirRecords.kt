package com.vayunmathur.health.domain

import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.data.VaccinationEntry
import java.time.Instant
import java.time.ZoneId

/**
 * Builds and reads the FHIR R4 JSON that Health Connect's Personal Health Record store accepts.
 *
 * Health Connect takes the resource as an opaque JSON string and validates it against the spec, so
 * the required fields below are not optional decoration: `Immunization` must carry `status`,
 * `vaccineCode`, `patient` and an `occurrence[x]`, and `MedicationRequest` and `MedicationStatement`
 * must carry `status`, `subject` and a `medication[x]`. All of them reference a `Patient`, which is
 * why [patientJson] exists and is written to the same data source before anything else.
 *
 * Reading is deliberately far more forgiving than writing. Resources synced from a real health system
 * are shown alongside the user's own, and those carry codings, extensions and date precisions this
 * app never produces; anything unrecognised is skipped rather than failing the import.
 *
 * The per-entity logic lives in same-package files ([FhirImmunization.kt][patientJson] siblings,
 * [FhirMedication.kt][medicationRequestJson], [FhirAllergyCondition.kt][allergyIntoleranceJson],
 * [FhirObservations.kt][labResultJson]) over shared JSON helpers in [FhirJson.kt]; this object is
 * the stable call site every reader and the tests use.
 */
object FhirRecords {

    /** The one synthetic patient every resource this app writes points at. */
    const val PATIENT_RESOURCE_ID = "ma-health-patient"

    const val CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx"
    const val RXNORM_SYSTEM = "http://www.nlm.nih.gov/research/umls/rxnorm"
    const val ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm"
    const val LOINC_SYSTEM = "http://loinc.org"
    const val SNOMED_SYSTEM = "http://snomed.info/sct"
    const val UCUM_SYSTEM = "http://unitsofmeasure.org"

    /** LOINC code for the pregnancy status observation US Core defines. */
    const val LOINC_PREGNANCY_STATUS = "82810-3"

    /** LOINC code for the tobacco smoking status observation US Core requires. */
    const val LOINC_SMOKING_STATUS = "72166-2"

    /**
     * Extension carrying a scanned vaccination card.
     *
     * None of the resource types Health Connect accepts has an attachment field, and
     * `DocumentReference` is not one of them, so the file itself is kept in app storage and only
     * referenced from here. The URL is a `file://` path that is meaningless to any other reader,
     * which is exactly why this is a custom extension rather than a misuse of a standard field.
     */
    const val ATTACHMENT_EXTENSION_URL =
        "https://vayunmathur.com/fhir/StructureDefinition/attachment"

    /** One attached file, as it appears inside an [ATTACHMENT_EXTENSION_URL] extension. */
    data class FhirAttachment(
        val contentType: String,
        val title: String,
        val url: String,
        val creation: Instant,
    )

    // --- Writing -------------------------------------------------------------

    fun patientJson(): String = com.vayunmathur.health.domain.patientJson()

    fun immunizationJson(
        entry: VaccinationEntry,
        attachments: List<FhirAttachment> = emptyList(),
    ): String = com.vayunmathur.health.domain.immunizationJson(entry, attachments)

    fun medicationRequestJson(
        entry: MedicationEntry,
        schedule: MedicationSchedule? = null,
    ): String = com.vayunmathur.health.domain.medicationRequestJson(entry, schedule)

    /** "Amoxicillin", "500 mg", "Capsule" rendered as one line for `display` and `text`. */
    fun fullMedicationName(entry: MedicationEntry): String =
        com.vayunmathur.health.domain.fullMedicationName(entry)

    fun allergyIntoleranceJson(entry: AllergyEntry): String =
        com.vayunmathur.health.domain.allergyIntoleranceJson(entry)

    fun conditionJson(entry: ConditionEntry): String =
        com.vayunmathur.health.domain.conditionJson(entry)

    // --- Reading -------------------------------------------------------------

    fun parseImmunization(data: String, dataSourceId: String): VaccinationEntry? =
        com.vayunmathur.health.domain.parseImmunization(data, dataSourceId)

    fun parseMedicationRequest(
        data: String,
        dataSourceId: String,
    ): Pair<MedicationEntry, MedicationSchedule?>? =
        com.vayunmathur.health.domain.parseMedicationRequest(data, dataSourceId)

    fun doseEventJson(event: DoseEvent, medication: MedicationEntry): String =
        com.vayunmathur.health.domain.doseEventJson(event, medication)

    fun parseDoseEvent(data: String, dataSourceId: String): DoseEvent? =
        com.vayunmathur.health.domain.parseDoseEvent(data, dataSourceId)

    fun parseMedicationStatement(data: String, dataSourceId: String): MedicationEntry? =
        com.vayunmathur.health.domain.parseMedicationStatement(data, dataSourceId)

    fun isUnplannedMedicationStatement(data: String): Boolean =
        com.vayunmathur.health.domain.isUnplannedMedicationStatement(data)

    fun isEnteredInError(data: String): Boolean =
        com.vayunmathur.health.domain.isEnteredInError(data)

    fun parseAllergyIntolerance(data: String, dataSourceId: String): AllergyEntry? =
        com.vayunmathur.health.domain.parseAllergyIntolerance(data, dataSourceId)

    fun parseCondition(data: String, dataSourceId: String): ConditionEntry? =
        com.vayunmathur.health.domain.parseCondition(data, dataSourceId)

    // --- Observations --------------------------------------------------------

    fun labResultJson(entry: LabResultEntry): String =
        com.vayunmathur.health.domain.labResultJson(entry)

    fun pregnancyStatusJson(
        id: String,
        status: PregnancyStatus,
        recordedAt: Instant,
        dueDate: Instant?,
    ): String = com.vayunmathur.health.domain.pregnancyStatusJson(id, status, recordedAt, dueDate)

    fun smokingStatusJson(id: String, status: SmokingStatus, recordedAt: Instant): String =
        com.vayunmathur.health.domain.smokingStatusJson(id, status, recordedAt)

    fun socialHistoryAnswerJson(
        id: String,
        question: SocialHistoryQuestions.Question,
        answer: SocialHistoryQuestions.Answer,
        recordedAt: Instant,
    ): String = com.vayunmathur.health.domain.socialHistoryAnswerJson(id, question, answer, recordedAt)


    fun parseLabResult(data: String, dataSourceId: String): LabResultEntry? =
        com.vayunmathur.health.domain.parseLabResult(data, dataSourceId)




    fun parsePregnancyStatus(data: String): Pair<PregnancyStatus, Instant?>? =
        com.vayunmathur.health.domain.parsePregnancyStatus(data)

    fun parseSmokingStatus(data: String): SmokingStatus? =
        com.vayunmathur.health.domain.parseSmokingStatus(data)

    // --- Shared parsing ------------------------------------------------------

    /**
     * FHIR `dateTime` allows YYYY, YYYY-MM and YYYY-MM-DD as well as a full timestamp, and a provider
     * feed uses all of them. Partial dates are anchored to local midnight in [zone], the same way
     * this app stores a date it picked itself, so they show on the day they name.
     */
    internal fun parseDateTime(text: String?, zone: ZoneId = ZoneId.systemDefault()): Instant? =
        com.vayunmathur.health.domain.parseDateTime(text, zone)

    /** Splits "0.5 mL" into 0.5 and "mL". Null when the text does not start with a number. */
    internal fun parseQuantity(text: String): Pair<Double, String>? =
        com.vayunmathur.health.domain.parseQuantity(text)
}

/** Observation-level FHIR helpers, split out of [FhirRecords]. */
object FhirObservations {
    fun parseSocialHistoryAnswer(data: String): String? =
        com.vayunmathur.health.domain.parseSocialHistoryAnswer(data)

    fun observationLoincCode(data: String): String? =
        com.vayunmathur.health.domain.observationLoincCode(data)

    /** The resource's own `id`, needed to update rather than duplicate it on the next write. */

    fun resourceId(data: String): String? =
        com.vayunmathur.health.domain.resourceId(data)

    fun observationEffective(data: String): Instant? =
        com.vayunmathur.health.domain.observationEffective(data)
}
