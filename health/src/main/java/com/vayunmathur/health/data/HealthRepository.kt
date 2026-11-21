package com.vayunmathur.health.data

import android.content.Context
import com.vayunmathur.health.domain.Centimeters
import com.vayunmathur.health.domain.Kilograms
import com.vayunmathur.health.domain.Liters
import com.vayunmathur.health.domain.Meters
import com.vayunmathur.health.domain.Percent
import com.vayunmathur.library.room.RoomRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of [HealthDatabase]. Replaces every `buildDatabase<HealthDatabase>()`
 * call site (MainActivity, HealthSyncWorker) and the global `HealthAPI.db` lateinit.
 */
// Delegation facade by design: one thin passthrough per DAO query so callers never
// touch a DAO directly. Splitting it would only move the same method count elsewhere.
@Suppress("TooManyFunctions")
class HealthRepository private constructor(context: Context) :
    RoomRepository<HealthDatabase>(context, HealthDatabase::class) {

    private val dao get() = db.healthDao()
    private val medicalDao get() = db.medicalDao()
    private val scheduleDao get() = db.scheduleDao()

    // ------------------------------------------------------------------
    // Flow passthroughs — keep DAO names so callers migrating off `HealthAPI.db.healthDao()` stay trivial
    // ------------------------------------------------------------------

    fun getRecordsFlow(type: RecordType): Flow<List<Record>> = dao.getRecordsFlow(type)
    suspend fun getLastRecord(type: RecordType): Record? = dao.getLastRecord(type)
    fun sumInRange(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): Flow<Double> = dao.sumInRange(type, startTime, endTime)
    fun sumNutritionInRange(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): Flow<NutritionData> = dao.sumNutritionInRange(type, startTime, endTime)
    fun minInRange(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): Flow<Double?> = dao.minInRange(type, startTime, endTime)
    fun maxInRange(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): Flow<Double?> = dao.maxInRange(type, startTime, endTime)
    fun getAllInRange(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): Flow<List<Record>> = dao.getAllInRange(type, startTime, endTime)
    suspend fun getDailySums(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): List<HealthDao.DailySum> = dao.getDailySums(type, startTime, endTime)
    suspend fun getHourlySums(
        type: RecordType,
        startTime: Long,
        endTime: Long,
    ): List<HealthDao.HourlySum> = dao.getHourlySums(type, startTime, endTime)
    suspend fun getDailyAvgs(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): List<HealthDao.DailySum> = dao.getDailyAvgs(type, startTime, endTime)
    suspend fun getHourlyAvgs(
        type: RecordType,
        startTime: Long,
        endTime: Long,
    ): List<HealthDao.HourlySum> = dao.getHourlyAvgs(type, startTime, endTime)

    suspend fun upsert(records: List<Record>) = dao.upsert(records)
    suspend fun deleteByIds(ids: List<String>) = dao.deleteByIds(ids)

    // ------------------------------------------------------------------
    // Typed write seam — unit-sensitive logging entry points.
    //
    // These are the repository boundary for the types whose stored unit is a
    // physical quantity (see RecordType.storedUnit): callers hand over a typed
    // value (Kilograms, Meters/Centimeters, Percent, Liters) and the record is
    // built by the TypedRecords factories, so the canonical stored double is
    // produced in exactly one place. Prefer these over building a Record by
    // hand for these types; the plain upsert above stays for the
    // non-unit-sensitive types, payload records and test fixtures.
    // ------------------------------------------------------------------

    suspend fun upsertWeight(id: String, time: Instant, weight: Kilograms) =
        upsert(listOf(storedWeightRecord(id, time, weight)))

    suspend fun upsertHeight(id: String, time: Instant, height: Meters) =
        upsert(listOf(storedHeightRecord(id, time, height)))

    /** Height as entered/displayed (cm): stored as metres via [Centimeters.toMeters]. */
    suspend fun upsertHeight(id: String, time: Instant, height: Centimeters) =
        upsert(listOf(storedHeightRecord(id, time, height)))

    suspend fun upsertBodyFat(id: String, time: Instant, bodyFat: Percent) =
        upsert(listOf(storedBodyFatRecord(id, time, bodyFat)))

    suspend fun upsertLeanBodyMass(id: String, time: Instant, mass: Kilograms) =
        upsert(listOf(storedLeanBodyMassRecord(id, time, mass)))

    suspend fun upsertBoneMass(id: String, time: Instant, mass: Kilograms) =
        upsert(listOf(storedBoneMassRecord(id, time, mass)))

    suspend fun upsertBodyWaterMass(id: String, time: Instant, mass: Kilograms) =
        upsert(listOf(storedBodyWaterMassRecord(id, time, mass)))

    suspend fun upsertHydration(id: String, start: Instant, end: Instant, volume: Liters) =
        upsert(listOf(storedHydrationRecord(id, start, end, volume)))

    // Ingredients / Recipes
    suspend fun insertIngredient(ingredient: Ingredient) = dao.insertIngredient(ingredient)
    suspend fun updateIngredient(ingredient: Ingredient) = dao.updateIngredient(ingredient)
    suspend fun deleteIngredient(ingredient: Ingredient) = dao.deleteIngredient(ingredient)
    fun getAllIngredientsFlow(): Flow<List<Ingredient>> = dao.getAllIngredientsFlow()
    fun getIngredientsAsRecipesFlow(): Flow<List<Ingredient>> = dao.getIngredientsAsRecipesFlow()
    suspend fun getIngredient(id: String): Ingredient? = dao.getIngredient(id)
    suspend fun searchIngredients(query: String): List<Ingredient> = dao.searchIngredients(query)

    suspend fun insertRecipe(recipe: Recipe) = dao.insertRecipe(recipe)
    suspend fun updateRecipe(recipe: Recipe) = dao.updateRecipe(recipe)
    suspend fun deleteRecipe(recipe: Recipe) = dao.deleteRecipe(recipe)
    fun getAllRecipesFlow(): Flow<List<Recipe>> = dao.getAllRecipesFlow()
    suspend fun getRecipe(id: String): Recipe? = dao.getRecipe(id)

    suspend fun insertServingUnit(unit: ServingUnit) = dao.insertServingUnit(unit)
    suspend fun deleteServingUnit(unit: ServingUnit) = dao.deleteServingUnit(unit)
    suspend fun getUnitsForIngredient(ingredientId: String): List<ServingUnit> = dao.getUnitsForIngredient(ingredientId)

    suspend fun insertRecipeIngredient(recipeIngredient: RecipeIngredient) =
        dao.insertRecipeIngredient(recipeIngredient)
    suspend fun deleteRecipeIngredient(recipeIngredient: RecipeIngredient) =
        dao.deleteRecipeIngredient(recipeIngredient)
    suspend fun getIngredientsForRecipe(recipeId: String): List<RecipeIngredient> =
        dao.getIngredientsForRecipe(recipeId)

    // Medical history — vaccinations, medications and their attachments
    suspend fun upsertVaccination(entry: VaccinationEntry) = medicalDao.upsertVaccination(entry)
    suspend fun deleteVaccination(entry: VaccinationEntry) = medicalDao.deleteVaccination(entry)
    fun getVaccinationsFlow(): Flow<List<VaccinationEntry>> = medicalDao.getVaccinationsFlow()
    suspend fun getVaccination(id: String): VaccinationEntry? = medicalDao.getVaccination(id)
    suspend fun findVaccination(dataSourceId: String, fhirResourceId: String): VaccinationEntry? =
        medicalDao.findVaccination(dataSourceId, fhirResourceId)

    suspend fun upsertMedication(entry: MedicationEntry) = medicalDao.upsertMedication(entry)
    suspend fun deleteMedication(entry: MedicationEntry) = medicalDao.deleteMedication(entry)
    fun getMedicationsFlow(): Flow<List<MedicationEntry>> = medicalDao.getMedicationsFlow()
    suspend fun getMedication(id: String): MedicationEntry? = medicalDao.getMedication(id)
    suspend fun findMedication(dataSourceId: String, fhirResourceId: String): MedicationEntry? =
        medicalDao.findMedication(dataSourceId, fhirResourceId)
    suspend fun findMedicationsByFhirId(fhirResourceId: String): List<MedicationEntry> =
        medicalDao.findMedicationsByFhirId(fhirResourceId)

    suspend fun insertAttachment(attachment: MedicalAttachment) = medicalDao.insertAttachment(attachment)
    suspend fun deleteAttachment(attachment: MedicalAttachment) = medicalDao.deleteAttachment(attachment)
    fun getAttachmentsFlow(): Flow<List<MedicalAttachment>> = medicalDao.getAttachmentsFlow()
    suspend fun getAttachmentsFor(vaccinationId: String): List<MedicalAttachment> =
        medicalDao.getAttachmentsFor(vaccinationId)

    // Dose schedules
    suspend fun upsertSchedule(schedule: MedicationSchedule) = scheduleDao.upsertSchedule(schedule)
    suspend fun deleteScheduleFor(medicationId: String) = scheduleDao.deleteScheduleFor(medicationId)
    suspend fun getScheduleFor(medicationId: String): MedicationSchedule? =
        scheduleDao.getScheduleFor(medicationId)
    suspend fun getSchedule(id: String): MedicationSchedule? = scheduleDao.getSchedule(id)
    fun getSchedulesFlow(): Flow<List<MedicationSchedule>> = scheduleDao.getSchedulesFlow()
    suspend fun getEnabledSchedules(): List<MedicationSchedule> = scheduleDao.getEnabledSchedules()

    // Allergies and conditions
    suspend fun upsertAllergy(entry: AllergyEntry) = medicalDao.upsertAllergy(entry)
    suspend fun deleteAllergy(entry: AllergyEntry) = medicalDao.deleteAllergy(entry)
    fun getAllergiesFlow(): Flow<List<AllergyEntry>> = medicalDao.getAllergiesFlow()
    suspend fun getAllergy(id: String): AllergyEntry? = medicalDao.getAllergy(id)
    suspend fun findAllergy(dataSourceId: String, fhirResourceId: String): AllergyEntry? =
        medicalDao.findAllergy(dataSourceId, fhirResourceId)

    suspend fun upsertCondition(entry: ConditionEntry) = medicalDao.upsertCondition(entry)
    suspend fun deleteCondition(entry: ConditionEntry) = medicalDao.deleteCondition(entry)
    fun getConditionsFlow(): Flow<List<ConditionEntry>> = medicalDao.getConditionsFlow()
    suspend fun getCondition(id: String): ConditionEntry? = medicalDao.getCondition(id)
    suspend fun findCondition(dataSourceId: String, fhirResourceId: String): ConditionEntry? =
        medicalDao.findCondition(dataSourceId, fhirResourceId)

    // Lab results and the standing profile
    suspend fun upsertLabResult(entry: LabResultEntry) = medicalDao.upsertLabResult(entry)
    suspend fun deleteLabResult(entry: LabResultEntry) = medicalDao.deleteLabResult(entry)
    fun getLabResultsFlow(): Flow<List<LabResultEntry>> = medicalDao.getLabResultsFlow()
    suspend fun getLabResult(id: String): LabResultEntry? = medicalDao.getLabResult(id)
    suspend fun findLabResult(dataSourceId: String, fhirResourceId: String): LabResultEntry? =
        medicalDao.findLabResult(dataSourceId, fhirResourceId)

    suspend fun upsertProfile(profile: HealthProfile) = medicalDao.upsertProfile(profile)
    fun getProfileFlow(): Flow<HealthProfile?> = medicalDao.getProfileFlow()
    suspend fun getProfile(): HealthProfile? = medicalDao.getProfile()

    suspend fun upsertProfileAnswer(answer: ProfileAnswer) = medicalDao.upsertProfileAnswer(answer)
    fun getProfileAnswersFlow(): Flow<List<ProfileAnswer>> = medicalDao.getProfileAnswersFlow()
    suspend fun getProfileAnswer(loincCode: String): ProfileAnswer? =
        medicalDao.getProfileAnswer(loincCode)
    suspend fun deleteProfileAnswer(loincCode: String) = medicalDao.deleteProfileAnswer(loincCode)

    // Doses taken
    suspend fun upsertDoseEvent(event: DoseEvent) = medicalDao.upsertDoseEvent(event)
    suspend fun deleteDoseEvent(event: DoseEvent) = medicalDao.deleteDoseEvent(event)
    fun getDoseEventsFlow(): Flow<List<DoseEvent>> = medicalDao.getDoseEventsFlow()
    suspend fun getRecentDoses(medicationId: String, limit: Int): List<DoseEvent> =
        medicalDao.getRecentDoses(medicationId, limit)
    suspend fun getDoseEvent(id: String): DoseEvent? = medicalDao.getDoseEvent(id)
    suspend fun deleteDosesFor(medicationId: String) = medicalDao.deleteDosesFor(medicationId)
    suspend fun getDosesFor(medicationId: String): List<DoseEvent> = medicalDao.getDosesFor(medicationId)
    suspend fun findDoseEvent(dataSourceId: String, fhirResourceId: String): DoseEvent? =
        medicalDao.findDoseEvent(dataSourceId, fhirResourceId)

    // Health Connect bookkeeping
    suspend fun upsertTombstone(tombstone: DeletedMedicalResource) = medicalDao.upsertTombstone(tombstone)
    suspend fun isTombstoned(kind: MedicalKind, dataSourceId: String, fhirResourceId: String): Boolean =
        medicalDao.isTombstoned(kind, dataSourceId, fhirResourceId)
    suspend fun getPendingRemoteDeletes(dataSourceId: String): List<DeletedMedicalResource> =
        medicalDao.getPendingRemoteDeletes(dataSourceId)
    suspend fun markPending(kind: MedicalKind, rowId: String) =
        medicalDao.insertPendingMirror(PendingMirror(kind, rowId))
    suspend fun clearPending(kind: MedicalKind, rowId: String) = medicalDao.deletePendingMirror(kind, rowId)
    suspend fun getPendingMirrors(): List<PendingMirror> = medicalDao.getPendingMirrors()

    /**
     * Expose underlying [HealthDatabase] for call sites that need transactional access
     * (prefer adding a method here instead).
     */
    internal val database: HealthDatabase get() = db

    /** Shared constants and helpers for HealthRepository. */
    companion object {
        @Volatile
        private var instance: HealthRepository? = null

        fun get(context: Context): HealthRepository =
            instance ?: synchronized(this) {
                instance ?: HealthRepository(context).also { instance = it }
            }
    }
}
