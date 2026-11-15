package com.vayunmathur.health.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * A single immunisation, mirrored to Health Connect as a FHIR `Immunization`.
 *
 * [fhirResourceId] and [dataSourceId] stay null until that mirror succeeds, which it never does on a
 * device without the Personal Health Record feature. The row is still complete and displayable
 * without them, which is what lets the feature work across the whole `minSdk 31` range.
 */
@Entity(indices = [Index(value = ["occurredAt"])])
data class VaccinationEntry(
    @PrimaryKey val id: String,
    /** CDC CVX code, or null for a free-text entry made with no catalogue available. */
    val cvxCode: String? = null,
    val displayName: String,
    val occurredAt: Instant,
    val lotNumber: String? = null,
    val site: String? = null,
    val route: String? = null,
    val doseQuantity: String? = null,
    val performer: String? = null,
    val note: String? = null,
    val fhirResourceId: String? = null,
    val dataSourceId: String? = null,
)

/** Whether a medication is still being taken. Maps to FHIR `MedicationRequest.status`. */
enum class MedicationStatus { Active, Completed, Stopped }

/**
 * A medication the user takes or has taken, mirrored to Health Connect as a FHIR
 * `MedicationRequest`. Same nullable-FHIR-columns arrangement as [VaccinationEntry].
 */
@Entity(indices = [Index(value = ["startedAt"])])
data class MedicationEntry(
    @PrimaryKey val id: String,
    /** RxNorm concept unique identifier, or null for a free-text entry. */
    val rxcui: String? = null,
    val displayName: String,
    val strength: String? = null,
    val doseForm: String? = null,
    val status: MedicationStatus = MedicationStatus.Active,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val dosageText: String? = null,
    val note: String? = null,
    val fhirResourceId: String? = null,
    val dataSourceId: String? = null,
)

/**
 * A PDF or photo attached to a vaccination — typically a photograph of a vaccination card.
 *
 * Only [fileName] is stored, never the `content://` Uri it was imported from: that Uri's read
 * permission does not survive a reboot. The bytes live in `filesDir/medical_attachments/`, managed by
 * `com.vayunmathur.health.platform.AttachmentStore`.
 */
@Entity(indices = [Index(value = ["vaccinationId"])])
data class MedicalAttachment(
    @PrimaryKey val id: String,
    val vaccinationId: String,
    val fileName: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val importedAt: Instant,
)

/** How severe a reaction to an allergen is expected to be. FHIR `AllergyIntolerance.criticality`. */
enum class AllergyCriticality { Low, High, Unknown }

/** What kind of thing the allergen is. FHIR `AllergyIntolerance.category`. */
enum class AllergyCategory { Medication, Food, Environment, Biologic }

/**
 * An allergy or intolerance, mirrored to Health Connect as a FHIR `AllergyIntolerance`.
 *
 * [rxcui] is set only for medication allergies, where the RxNorm catalogue can supply a code; food
 * and environmental allergens have no bundled terminology so they are recorded by name alone. That
 * is a real limitation of what can be shipped offline rather than a shortcut — SNOMED CT would code
 * all four categories but needs a UMLS licence.
 */
@Entity(indices = [Index(value = ["recordedAt"])])
data class AllergyEntry(
    @PrimaryKey val id: String,
    val rxcui: String? = null,
    val displayName: String,
    val category: AllergyCategory = AllergyCategory.Medication,
    val criticality: AllergyCriticality = AllergyCriticality.Unknown,
    /** What actually happens, free text: "hives", "anaphylaxis". */
    val reaction: String? = null,
    val onsetAt: Instant? = null,
    val recordedAt: Instant,
    val note: String? = null,
    val fhirResourceId: String? = null,
    val dataSourceId: String? = null,
)

/**
 * One dose actually taken, mirrored to Health Connect as a completed FHIR `MedicationStatement`.
 *
 * Only doses the user acknowledged are recorded. A reminder that rang and was ignored writes
 * nothing, because the app has no idea whether the dose was taken anyway — inferring "missed" from
 * an untapped notification would put a clinical claim in the record that nobody made.
 */
@Entity(indices = [Index(value = ["medicationId", "takenAt"])])
data class DoseEvent(
    @PrimaryKey val id: String,
    val medicationId: String,
    val takenAt: Instant,
    val fhirResourceId: String? = null,
    val dataSourceId: String? = null,
)

/** Where a condition stands now. FHIR `Condition.clinicalStatus`. */
enum class ConditionStatus { Active, Recurrence, Remission, Resolved }

/** Answers to FHIR's pregnancy status observation, LOINC 82810-3. */
enum class PregnancyStatus { Unknown, Pregnant, NotPregnant }

/** Answers to FHIR's tobacco smoking status observation, LOINC 72166-2. */
enum class SmokingStatus { Unknown, Never, Former, Current }

/**
 * A lab result, mirrored to Health Connect as a FHIR `Observation` in the `laboratory` category.
 *
 * Most results are a number and a unit, but plenty are qualitative — "positive", "trace", "not
 * detected" — so [value] and [valueText] are alternatives rather than both being filled. FHIR models
 * that the same way, as `valueQuantity` against `valueString`.
 */
@Entity(indices = [Index(value = ["takenAt"])])
data class LabResultEntry(
    @PrimaryKey val id: String,
    val loincCode: String? = null,
    val displayName: String,
    /** The numeric result, or null when the result is qualitative. */
    val value: Double? = null,
    /** The qualitative result, or null when the result is numeric. */
    val valueText: String? = null,
    /** UCUM unit, e.g. "mg/dL". Only meaningful alongside [value]. */
    val unit: String? = null,
    val referenceLow: Double? = null,
    val referenceHigh: Double? = null,
    val takenAt: Instant,
    val note: String? = null,
    val fhirResourceId: String? = null,
    val dataSourceId: String? = null,
) {
    /** Whether a numeric result sits outside its own reference range, when it has one. */
    val isOutOfRange: Boolean
        get() {
            val v = value ?: return false
            referenceLow?.let { if (v < it) return true }
            referenceHigh?.let { if (v > it) return true }
            return false
        }
}

/**
 * One answered social history question, keyed by the LOINC code of the question.
 *
 * A row per answer rather than a column per question, because C-CDA's social history covers
 * occupation, lifestyle and environmental risk factors as well as the six asked today — the set will
 * grow, and growing it should not mean a migration each time. The questions and their permitted
 * answers live in `SocialHistoryQuestions`.
 */
@Entity
data class ProfileAnswer(
    @PrimaryKey val loincCode: String,
    /** LOINC LA code for the chosen answer. */
    val answerCode: String,
    val recordedAt: Instant,
    val fhirResourceId: String? = null,
    val dataSourceId: String? = null,
)

/**
 * The handful of standing facts about the user that FHIR models as observations rather than events.
 *
 * One row, always. Pregnancy status and smoking status are each a current answer plus the date it
 * was given — a clinician wants to know what is true now, not to scroll a history — so they are
 * fields here rather than a log of their own. Each mirrors to its own `Observation`, which is why
 * there are two FHIR ids.
 */
@Entity
data class HealthProfile(
    @PrimaryKey val id: String = SINGLETON_ID,
    val pregnancyStatus: PregnancyStatus = PregnancyStatus.Unknown,
    val dueDate: Instant? = null,
    val pregnancyRecordedAt: Instant? = null,
    val pregnancyFhirId: String? = null,
    val smokingStatus: SmokingStatus = SmokingStatus.Unknown,
    val smokingRecordedAt: Instant? = null,
    val smokingFhirId: String? = null,
    val dataSourceId: String? = null,
) {
    /** Shared constants and helpers for HealthProfile. */
    companion object {
        /** There is only ever one profile, so its key is a constant rather than a generated id. */
        const val SINGLETON_ID = "profile"
    }
}

/**
 * A diagnosis, mirrored to Health Connect as a FHIR `Condition`.
 *
 * [icd10Code] comes from the bundled ICD-10-CM catalogue and is stored dotted ("E11.9"), the form
 * FHIR expects.
 */
@Entity(indices = [Index(value = ["onsetAt"])])
data class ConditionEntry(
    @PrimaryKey val id: String,
    val icd10Code: String? = null,
    val displayName: String,
    val status: ConditionStatus = ConditionStatus.Active,
    val onsetAt: Instant,
    val resolvedAt: Instant? = null,
    val note: String? = null,
    val fhirResourceId: String? = null,
    val dataSourceId: String? = null,
)

/** Which medical table a [DeletedMedicalResource] or [PendingMirror] refers to. */
enum class MedicalKind { Vaccination, Medication, Dose, Allergy, Condition, LabResult }

/**
 * A Health Connect resource the user deleted here, so the next import does not bring it back.
 *
 * Another app's records are read-only to this one, and deleting our own can fail or happen while
 * the feature is unavailable. [retryDelete] is true while our own copy still needs removing.
 */
@Entity(primaryKeys = ["kind", "dataSourceId", "fhirResourceId"])
data class DeletedMedicalResource(
    val kind: MedicalKind,
    val dataSourceId: String,
    val fhirResourceId: String,
    val fhirResourceType: Int,
    val retryDelete: Boolean,
)

/** A row whose latest local state has not reached Health Connect yet. */
@Entity(primaryKeys = ["kind", "rowId"])
data class PendingMirror(val kind: MedicalKind, val rowId: String)

@Dao
// Query surface of the medical tables
// Splitting the interface would only move the same method count to more files.
/** Contract for medical dao. */
@Suppress("TooManyFunctions")
interface MedicalDao {

    // --- Vaccinations ------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVaccination(entry: VaccinationEntry)

    @Update
    suspend fun updateVaccination(entry: VaccinationEntry)

    @Delete
    suspend fun deleteVaccination(entry: VaccinationEntry)

    @Query("SELECT * FROM VaccinationEntry ORDER BY occurredAt DESC")
    fun getVaccinationsFlow(): Flow<List<VaccinationEntry>>

    @Query("SELECT * FROM VaccinationEntry WHERE id = :id")
    suspend fun getVaccination(id: String): VaccinationEntry?

    @Query(
        "SELECT * FROM VaccinationEntry " +
            "WHERE dataSourceId = :dataSourceId AND fhirResourceId = :fhirResourceId LIMIT 1"
    )
    suspend fun findVaccination(dataSourceId: String, fhirResourceId: String): VaccinationEntry?

    // --- Medications -------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedication(entry: MedicationEntry)

    @Update
    suspend fun updateMedication(entry: MedicationEntry)

    @Delete
    suspend fun deleteMedication(entry: MedicationEntry)

    @Query("SELECT * FROM MedicationEntry ORDER BY startedAt DESC")
    fun getMedicationsFlow(): Flow<List<MedicationEntry>>

    @Query("SELECT * FROM MedicationEntry WHERE id = :id")
    suspend fun getMedication(id: String): MedicationEntry?

    @Query(
        "SELECT * FROM MedicationEntry " +
            "WHERE dataSourceId = :dataSourceId AND fhirResourceId = :fhirResourceId LIMIT 1"
    )
    suspend fun findMedication(dataSourceId: String, fhirResourceId: String): MedicationEntry?

    @Query("SELECT * FROM MedicationEntry WHERE fhirResourceId = :fhirResourceId")
    suspend fun findMedicationsByFhirId(fhirResourceId: String): List<MedicationEntry>

    // --- Attachments -------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: MedicalAttachment)

    @Delete
    suspend fun deleteAttachment(attachment: MedicalAttachment)

    @Query("SELECT * FROM MedicalAttachment ORDER BY importedAt ASC")
    fun getAttachmentsFlow(): Flow<List<MedicalAttachment>>

    @Query("SELECT * FROM MedicalAttachment WHERE vaccinationId = :vaccinationId ORDER BY importedAt ASC")
    suspend fun getAttachmentsFor(vaccinationId: String): List<MedicalAttachment>

    // --- Allergies ---------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllergy(entry: AllergyEntry)

    @Delete
    suspend fun deleteAllergy(entry: AllergyEntry)

    /** Most critical first: an allergy list is read to find the dangerous one. */
    @Query("SELECT * FROM AllergyEntry ORDER BY criticality = 'High' DESC, displayName ASC")
    fun getAllergiesFlow(): Flow<List<AllergyEntry>>

    @Query("SELECT * FROM AllergyEntry WHERE id = :id")
    suspend fun getAllergy(id: String): AllergyEntry?

    @Query(
        "SELECT * FROM AllergyEntry " +
            "WHERE dataSourceId = :dataSourceId AND fhirResourceId = :fhirResourceId LIMIT 1"
    )
    suspend fun findAllergy(dataSourceId: String, fhirResourceId: String): AllergyEntry?

    // --- Conditions --------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCondition(entry: ConditionEntry)

    @Delete
    suspend fun deleteCondition(entry: ConditionEntry)

    @Query("SELECT * FROM ConditionEntry ORDER BY onsetAt DESC")
    fun getConditionsFlow(): Flow<List<ConditionEntry>>

    @Query("SELECT * FROM ConditionEntry WHERE id = :id")
    suspend fun getCondition(id: String): ConditionEntry?

    @Query(
        "SELECT * FROM ConditionEntry " +
            "WHERE dataSourceId = :dataSourceId AND fhirResourceId = :fhirResourceId LIMIT 1"
    )
    suspend fun findCondition(dataSourceId: String, fhirResourceId: String): ConditionEntry?

    // --- Lab results -------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabResult(entry: LabResultEntry)

    @Delete
    suspend fun deleteLabResult(entry: LabResultEntry)

    @Query("SELECT * FROM LabResultEntry ORDER BY takenAt DESC")
    fun getLabResultsFlow(): Flow<List<LabResultEntry>>

    @Query("SELECT * FROM LabResultEntry WHERE id = :id")
    suspend fun getLabResult(id: String): LabResultEntry?

    @Query(
        "SELECT * FROM LabResultEntry " +
            "WHERE dataSourceId = :dataSourceId AND fhirResourceId = :fhirResourceId LIMIT 1"
    )
    suspend fun findLabResult(dataSourceId: String, fhirResourceId: String): LabResultEntry?

    // --- Profile -----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: HealthProfile)

    @Query("SELECT * FROM HealthProfile WHERE id = :id")
    fun getProfileFlow(id: String = HealthProfile.SINGLETON_ID): Flow<HealthProfile?>

    @Query("SELECT * FROM HealthProfile WHERE id = :id")
    suspend fun getProfile(id: String = HealthProfile.SINGLETON_ID): HealthProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfileAnswer(answer: ProfileAnswer)

    @Query("SELECT * FROM ProfileAnswer")
    fun getProfileAnswersFlow(): Flow<List<ProfileAnswer>>

    @Query("SELECT * FROM ProfileAnswer WHERE loincCode = :loincCode")
    suspend fun getProfileAnswer(loincCode: String): ProfileAnswer?

    @Query("DELETE FROM ProfileAnswer WHERE loincCode = :loincCode")
    suspend fun deleteProfileAnswer(loincCode: String)

    // --- Dose events -------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDoseEvent(event: DoseEvent)

    @Delete
    suspend fun deleteDoseEvent(event: DoseEvent)

    @Query("SELECT * FROM DoseEvent ORDER BY takenAt DESC")
    fun getDoseEventsFlow(): Flow<List<DoseEvent>>

    @Query("SELECT * FROM DoseEvent WHERE medicationId = :medicationId ORDER BY takenAt DESC LIMIT :limit")
    suspend fun getRecentDoses(medicationId: String, limit: Int): List<DoseEvent>

    @Query("SELECT * FROM DoseEvent WHERE id = :id")
    suspend fun getDoseEvent(id: String): DoseEvent?

    @Query("DELETE FROM DoseEvent WHERE medicationId = :medicationId")
    suspend fun deleteDosesFor(medicationId: String)

    @Query("SELECT * FROM DoseEvent WHERE medicationId = :medicationId")
    suspend fun getDosesFor(medicationId: String): List<DoseEvent>

    @Query(
        "SELECT * FROM DoseEvent " +
            "WHERE dataSourceId = :dataSourceId AND fhirResourceId = :fhirResourceId LIMIT 1"
    )
    suspend fun findDoseEvent(dataSourceId: String, fhirResourceId: String): DoseEvent?

    // --- Health Connect bookkeeping ---------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTombstone(tombstone: DeletedMedicalResource)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM DeletedMedicalResource WHERE kind = :kind " +
            "AND dataSourceId = :dataSourceId AND fhirResourceId = :fhirResourceId)"
    )
    suspend fun isTombstoned(kind: MedicalKind, dataSourceId: String, fhirResourceId: String): Boolean

    @Query("SELECT * FROM DeletedMedicalResource WHERE retryDelete = 1 AND dataSourceId = :dataSourceId")
    suspend fun getPendingRemoteDeletes(dataSourceId: String): List<DeletedMedicalResource>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingMirror(pending: PendingMirror)

    @Query("DELETE FROM PendingMirror WHERE kind = :kind AND rowId = :rowId")
    suspend fun deletePendingMirror(kind: MedicalKind, rowId: String)

    @Query("SELECT * FROM PendingMirror")
    suspend fun getPendingMirrors(): List<PendingMirror>
}
