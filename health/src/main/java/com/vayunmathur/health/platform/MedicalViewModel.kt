@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.HealthProfile
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicalAttachment
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.ProfileAnswer
import com.vayunmathur.health.data.RepeatUnit
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.data.VaccinationEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toLocalDateTime
import java.time.Instant
import kotlin.time.Clock

/** Today in the device's timezone. The default date on both add forms. */
internal fun today(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

/** Midnight local time on this date, which is the precision a medical record carries. */
internal fun LocalDate.toInstant(): Instant =
    Instant.ofEpochMilli(atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds())

/** The local calendar date an [Instant] falls on. */
internal fun Instant.toLocalDate(): LocalDate =
    atZone(java.time.ZoneId.systemDefault()).toLocalDate().toKotlinLocalDate()

internal fun String.blankToNull(): String? = trim().ifBlank { null }

/**
 * Owns the Medication and Medical History screens.
 *
 * Separate from `HealthViewModel` rather than bolted onto it: that one is already over 600 lines and
 * covers an unrelated set of Health Connect record types.
 *
 * Room is the source of truth and Health Connect is a mirror written afterwards. That ordering is
 * what makes the feature work at all below Android 15, where the Personal Health Record API does not
 * exist, and it means a rejected or failed FHIR write costs the user nothing — the row is already
 * saved and stays queued in `PendingMirror` until a sync gets it written.
 *
 * The save/delete/mirror/import operations live in same-package files
 * ([MedicalVaccinationOps.kt][saveVaccinationDraft], [MedicalMedicationOps.kt][saveMedicationDraft],
 * [MedicalClinicalOps.kt][saveAllergyDraft], [MedicalProfileOps.kt][setPregnancyStatus],
 * [MedicalImportOps.kt][importFromHealthConnect]) as extensions over the internals below.
 */
class MedicalViewModel(
    application: Application,
    internal val repository: HealthRepository = HealthRepository.get(application),
) : AndroidViewModel(application) {

    /** Shared constants and helpers for MedicalViewModel. */
    companion object {
        /** 9am in seconds since midnight: the default time of a new reminder schedule. */
        internal const val DEFAULT_DOSE_TIME_SECONDS = 9 * 60 * 60
    }

    val vaccinations: StateFlow<List<VaccinationEntry>> = repository.getVaccinationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medications: StateFlow<List<MedicationEntry>> = repository.getMedicationsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allergies: StateFlow<List<AllergyEntry>> = repository.getAllergiesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conditions: StateFlow<List<ConditionEntry>> = repository.getConditionsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val labResults: StateFlow<List<LabResultEntry>> = repository.getLabResultsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The standing facts — pregnancy and smoking status. Never null once first written. */
    val profile: StateFlow<HealthProfile> = repository.getProfileFlow()
        .map { it ?: HealthProfile() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthProfile())

    /** Answered social history questions, keyed by the LOINC code of the question. */
    val profileAnswers: StateFlow<Map<String, ProfileAnswer>> = repository.getProfileAnswersFlow()
        .map { all -> all.associateBy { it.loincCode } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Attachments keyed by the vaccination they belong to. */
    val attachments: StateFlow<Map<String, List<MedicalAttachment>>> =
        repository.getAttachmentsFlow()
            .map { all -> all.groupBy { it.vaccinationId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Reminder schedules keyed by the medication they belong to. */
    val schedules: StateFlow<Map<String, MedicationSchedule>> =
        repository.getSchedulesFlow()
            .map { all -> all.associateBy { it.medicationId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Every dose taken, newest first. */
    val doseEvents: StateFlow<List<DoseEvent>> = repository.getDoseEventsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    internal val syncingState = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = syncingState.asStateFlow()

    /**
     * Whether this device can mirror to Health Connect at all. Drives an explanatory banner.
     *
     * Read once: the answer depends on the platform's Health Connect module, which cannot change
     * while the process is alive, and the screens read it on every recomposition.
     */
    val healthConnectAvailable: Boolean by lazy { PersonalHealthRecords.isAvailable() }

    // --- In-progress forms ---------------------------------------------------

    /**
     * The half-filled add forms live here rather than in `remember` inside the screens.
     *
     * `NavDisplay` composes only the top destination, so pushing the full-screen catalogue picker
     * disposes the form underneath it. Anything held in `remember` would be gone by the time the
     * user came back, and a result posted through `LocalNavResultRegistry` would land on a
     * collector that no longer exists — its `SharedFlow` has no replay. Keeping the draft here is
     * what makes the picker able to fill a field in at all, and it stops the rest of the form being
     * quietly wiped on the way past.
     */
    data class VaccinationDraft(
        /**
         * The row being edited, or null when adding.
         *
         * Reused as the FHIR resource id on save, so editing re-upserts the same Health Connect
         * resource rather than orphaning the old one and writing a second.
         */
        val editingId: String? = null,
        val cvxCode: String? = null,
        val displayName: String = "",
        val occurredOn: LocalDate = today(),
        val lotNumber: String = "",
        val site: String = "",
        val route: String = "",
        val dose: String = "",
        val performer: String = "",
        val note: String = "",
        /** Attachments already on disk, from a row being edited. */
        val savedAttachments: List<MedicalAttachment> = emptyList(),
        /**
         * Saved attachments the user has removed. Their files are deleted on save, not on tap, so
         * backing out of an edit cannot destroy a file the record still references.
         */
        val removedAttachmentIds: Set<String> = emptySet(),
        /** Newly picked files, held as strings so the draft stays comparable. */
        val attachmentUris: List<String> = emptyList(),
    ) {
        /** The saved attachments still to be shown, i.e. not pending removal. */
        val visibleSavedAttachments: List<MedicalAttachment>
            get() = savedAttachments.filterNot { it.id in removedAttachmentIds }
    }

    /** Immutable value describing medication draft. */
    data class MedicationDraft(
        val editingId: String? = null,
        val rxcui: String? = null,
        val ingredient: String = "",
        val strength: String? = null,
        val doseForm: String? = null,
        val status: MedicationStatus = MedicationStatus.Active,
        val startedOn: LocalDate = today(),
        val endedOn: LocalDate? = null,
        val dosage: String = "",
        val note: String = "",
        // --- Reminder schedule ---
        val scheduleId: String? = null,
        val remindersEnabled: Boolean = false,
        /** Seconds since midnight, one per dose. */
        val times: List<Int> = listOf(DEFAULT_DOSE_TIME_SECONDS),
        val repeatUnit: RepeatUnit = RepeatUnit.Daily,
        val interval: Int = 1,
        val daysOfWeek: Int = 0,
        val anchorDate: LocalDate = today(),
        val remindersUntil: LocalDate? = null,
        /**
         * Which entry in [times] the time picker is currently editing, or [times].size to append.
         *
         * One picker route serves every row, so the index has to live somewhere the picker's result
         * can be applied against — and the form is not composed while a dialog is up.
         */
        val editingTimeIndex: Int? = null,
    )

    internal val vaccinationDraftState = MutableStateFlow(VaccinationDraft())
    val vaccinationDraft: StateFlow<VaccinationDraft> = vaccinationDraftState.asStateFlow()

    internal val medicationDraftState = MutableStateFlow(MedicationDraft())
    val medicationDraft: StateFlow<MedicationDraft> = medicationDraftState.asStateFlow()

    /** Immutable value describing allergy draft. */
    data class AllergyDraft(
        val editingId: String? = null,
        val rxcui: String? = null,
        val displayName: String = "",
        val category: AllergyCategory = AllergyCategory.Medication,
        val criticality: AllergyCriticality = AllergyCriticality.Unknown,
        val reaction: String = "",
        val onsetOn: LocalDate? = null,
        val note: String = "",
    )

    /** Immutable value describing condition draft. */
    data class ConditionDraft(
        val editingId: String? = null,
        val icd10Code: String? = null,
        val displayName: String = "",
        val status: ConditionStatus = ConditionStatus.Active,
        val onsetOn: LocalDate = today(),
        val resolvedOn: LocalDate? = null,
        val note: String = "",
    )

    /** Immutable value describing lab result draft. */
    data class LabResultDraft(
        val editingId: String? = null,
        val loincCode: String? = null,
        val displayName: String = "",
        /** Free text while being typed; parsed to a number on save if it is one. */
        val value: String = "",
        val unit: String = "",
        val referenceLow: String = "",
        val referenceHigh: String = "",
        val takenOn: LocalDate = today(),
        val note: String = "",
    )

    internal val allergyDraftState = MutableStateFlow(AllergyDraft())
    val allergyDraft: StateFlow<AllergyDraft> = allergyDraftState.asStateFlow()

    internal val conditionDraftState = MutableStateFlow(ConditionDraft())
    val conditionDraft: StateFlow<ConditionDraft> = conditionDraftState.asStateFlow()

    fun startAllergyDraft(id: String? = null) {
        allergyDraftState.value = AllergyDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getAllergy(id) ?: return@launch
            allergyDraftState.value = AllergyDraft(
                editingId = entry.id,
                rxcui = entry.rxcui,
                displayName = entry.displayName,
                category = entry.category,
                criticality = entry.criticality,
                reaction = entry.reaction.orEmpty(),
                onsetOn = entry.onsetAt?.toLocalDate(),
                note = entry.note.orEmpty(),
            )
        }
    }

    fun editAllergyDraft(transform: (AllergyDraft) -> AllergyDraft) {
        allergyDraftState.update(transform)
    }

    fun startConditionDraft(id: String? = null) {
        conditionDraftState.value = ConditionDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getCondition(id) ?: return@launch
            conditionDraftState.value = ConditionDraft(
                editingId = entry.id,
                icd10Code = entry.icd10Code,
                displayName = entry.displayName,
                status = entry.status,
                onsetOn = entry.onsetAt.toLocalDate(),
                resolvedOn = entry.resolvedAt?.toLocalDate(),
                note = entry.note.orEmpty(),
            )
        }
    }

    fun editConditionDraft(transform: (ConditionDraft) -> ConditionDraft) {
        conditionDraftState.update(transform)
    }

    internal val labDraftState = MutableStateFlow(LabResultDraft())
    val labDraft: StateFlow<LabResultDraft> = labDraftState.asStateFlow()

    fun startLabDraft(id: String? = null) {
        labDraftState.value = LabResultDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getLabResult(id) ?: return@launch
            labDraftState.value = LabResultDraft(
                editingId = entry.id,
                loincCode = entry.loincCode,
                displayName = entry.displayName,
                value = entry.value?.let { formatNumber(it) } ?: entry.valueText.orEmpty(),
                unit = entry.unit.orEmpty(),
                referenceLow = entry.referenceLow?.let { formatNumber(it) }.orEmpty(),
                referenceHigh = entry.referenceHigh?.let { formatNumber(it) }.orEmpty(),
                takenOn = entry.takenAt.toLocalDate(),
                note = entry.note.orEmpty(),
            )
        }
    }

    fun editLabDraft(transform: (LabResultDraft) -> LabResultDraft) {
        labDraftState.update(transform)
    }

    /**
     * Prepares the vaccination form: blank for a new record, or loaded from [id] to edit one.
     *
     * Called when the user opens the form, not when it is composed — the form is disposed and
     * recomposed every time a picker opens over it, and reloading there would discard their edits.
     */
    fun startVaccinationDraft(id: String? = null) {
        vaccinationDraftState.value = VaccinationDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getVaccination(id) ?: return@launch
            vaccinationDraftState.value = VaccinationDraft(
                editingId = entry.id,
                cvxCode = entry.cvxCode,
                displayName = entry.displayName,
                occurredOn = entry.occurredAt.toLocalDate(),
                lotNumber = entry.lotNumber.orEmpty(),
                site = entry.site.orEmpty(),
                route = entry.route.orEmpty(),
                dose = entry.doseQuantity.orEmpty(),
                performer = entry.performer.orEmpty(),
                note = entry.note.orEmpty(),
                savedAttachments = repository.getAttachmentsFor(entry.id),
            )
        }
    }

    fun editVaccinationDraft(transform: (VaccinationDraft) -> VaccinationDraft) {
        vaccinationDraftState.update(transform)
    }

    fun startMedicationDraft(id: String? = null) {
        medicationDraftState.value = MedicationDraft()
        if (id == null) return
        viewModelScope.launch {
            val entry = repository.getMedication(id) ?: return@launch
            val schedule = repository.getScheduleFor(id)
            medicationDraftState.value = MedicationDraft(
                editingId = entry.id,
                rxcui = entry.rxcui,
                ingredient = entry.displayName,
                strength = entry.strength,
                doseForm = entry.doseForm,
                status = entry.status,
                startedOn = entry.startedAt.toLocalDate(),
                endedOn = entry.endedAt?.toLocalDate(),
                dosage = entry.dosageText.orEmpty(),
                note = entry.note.orEmpty(),
                scheduleId = schedule?.id,
                // A stopped medication's schedule is kept but switched off; showing it as on is
                // what lets reactivating the medication bring its reminders back.
                remindersEnabled = schedule != null &&
                    (schedule.enabled || entry.status != MedicationStatus.Active),
                times = schedule?.times?.takeIf { it.isNotEmpty() }
                    ?: listOf(DEFAULT_DOSE_TIME_SECONDS),
                repeatUnit = schedule?.repeatUnit ?: RepeatUnit.Daily,
                interval = schedule?.interval ?: 1,
                daysOfWeek = schedule?.daysOfWeek ?: 0,
                anchorDate = schedule?.anchorDate ?: today(),
                remindersUntil = schedule?.endDate,
            )
        }
    }

    fun editMedicationDraft(transform: (MedicationDraft) -> MedicationDraft) {
        medicationDraftState.update(transform)
    }
}

/** Trims a trailing ".0" so an integral result edits back as "12" rather than "12.0". */
internal fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** Creates medical view model instances. */
class MedicalViewModelFactory(
    private val application: Application,
    private val repository: HealthRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MedicalViewModel::class.java))
        return MedicalViewModel(application, repository) as T
    }
}
