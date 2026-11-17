@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.platform
import com.vayunmathur.health.data.FoodDatabase
import com.vayunmathur.health.data.FoodSearchAPI
import com.vayunmathur.health.data.HealthAPI
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.is24Hour
import kotlinx.datetime.isoDayNumber
import kotlin.uuid.Uuid
import android.app.Application
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.health.R
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecipeIngredient
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.HistoryItem
import com.vayunmathur.health.ui.MetricDashboardData
import com.vayunmathur.library.util.Tuple4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.toKotlinLocalDate
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * ViewModel for the Health app.
 *
 * Owns:
 *  - All HealthConnect / Room health-record queries previously called from composables
 *    (point-in-time metrics for the main page, bar/line chart aggregations).
 *  - Nutrition / hydration logging writes (Room + HealthConnect insert).
 *  - Recipe / Ingredient CRUD (Room).
 *  - PDF → image conversion + InferenceService dispatch for the OpenAssistant extraction flow.
 *
 * Composables should:
 *  - Use `viewModel.<metric>InRange(type, start, end).collectAsState(0.0)` for flow-based reads.
 *  - Call `viewModel.loadMainPageMetrics()`, `viewModel.loadBarChartData(...)`,
 *    etc. from a single `LaunchedEffect` and collect the resulting `StateFlow`.
 *  - Keep purely-UI state (dialog visibility, pager state, focus, text-field cursor) in compose.
 */
class HealthViewModel(
    application: Application,
    private val repository: HealthRepository = HealthRepository.get(application),
) : AndroidViewModel(application) {

    // ============================================================================================
    //  Flow getters — direct passthrough to the repository so callers can `collectAsState(...)`.
    //  Composables wrap them in `remember(...)` when the keys depend on changing values to avoid
    //  re-subscribing every recomposition.
    // ============================================================================================

    fun sumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        repository.sumInRange(type, start, end)

    fun sumNutritionInRange(
        type: RecordType,
        start: kotlin.time.Instant,
        end: kotlin.time.Instant,
    ): Flow<NutritionData> = repository.sumNutritionInRange(type, start, end)

    fun maxInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double?> =
        repository.maxInRange(type, start, end)

    fun minInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double?> =
        repository.minInRange(type, start, end)

    fun getAllRecordsInRange(
        type: RecordType,
        start: kotlin.time.Instant,
        end: kotlin.time.Instant,
    ): Flow<List<Record>> = repository.getAllInRange(type, start, end)

    fun getAllRecordsOfType(type: RecordType): Flow<List<Record>> =
        repository.getRecordsFlow(type)

    /**
     * Selects the sleep session to show for [day]: searches the window from 12h before the day's
     * start through the day's end and returns the latest-ending session whose end falls on [day].
     * Record-selection business logic lives here, not in the composable.
     */
    fun sleepRecordForDay(day: LocalDate): Flow<Record?> {
        val tz = TimeZone.currentSystemDefault()
        val searchStart = day.atStartOfDayIn(tz).minus(12.hours)
        val searchEnd = day.atStartOfDayIn(tz).plus(24.hours)
        return repository.getAllInRange(RecordType.Sleep, searchStart, searchEnd).map { records ->
            records.filter {
                val endLocal = it.endTime.atZone(ZoneId.systemDefault()).toLocalDate().toKotlinLocalDate()
                endLocal == day
            }.maxByOrNull { it.endTime }
        }
    }

    // ============================================================================================
    //  Recipe / Ingredient flows.
    // ============================================================================================

    val allRecipes: Flow<List<Recipe>> get() = repository.getAllRecipesFlow()
    val allIngredients: Flow<List<Ingredient>> get() = repository.getAllIngredientsFlow()
    val ingredientsAsRecipes: Flow<List<Ingredient>> get() = repository.getIngredientsAsRecipesFlow()

    // ============================================================================================
    //  Main page point-in-time metric bundle.
    // ============================================================================================

    private val _mainPageMetrics = MutableStateFlow(MainPageMetrics())
    val mainPageMetrics: StateFlow<MainPageMetrics> = _mainPageMetrics.asStateFlow()

    fun loadMainPageMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            // Publish each metric to the StateFlow the moment its query resolves,
            // rather than awaiting all ~15 and assigning once. The Today dashboard
            // only shows sleep / blood pressure / SpO2 / resting-HR; the other 11
            // point-in-time metrics feed the Body page. Bulk-awaiting gated those
            // four visible vitals on the slowest of all fifteen queries, so the
            // dashboard sat empty until unrelated body-composition reads finished.
            // Each launch below runs on Room's pool in parallel and updates one
            // field, so the visible vitals appear as soon as they're read and the
            // rest fill in progressively. The final aggregate state is unchanged.
            coroutineScope {
                loadVitalsMetrics()
                loadBodyMetrics()
            }
        }
    }

    /** Dashboard vitals: the four Today shows plus the other point-in-time vitals. */
    private suspend fun CoroutineScope.loadVitalsMetrics() {
        launch {
            val v = HealthAPI.lastRecord(RecordType.OxygenSaturation)?.value
            _mainPageMetrics.update { it.copy(spo2 = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.RespiratoryRate)?.value
            _mainPageMetrics.update { it.copy(br = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.HeartRateVariabilityRmssd)?.value
            _mainPageMetrics.update { it.copy(hrv = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.RestingHeartRate)?.value?.toLong()
            _mainPageMetrics.update { it.copy(rhr = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.SkinTemperature)?.value
            _mainPageMetrics.update { it.copy(skinTemp = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.Vo2Max)?.value
            _mainPageMetrics.update { it.copy(vo2Max = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.BloodGlucose)?.value
            _mainPageMetrics.update { it.copy(bloodGlucose = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.BloodPressure)?.let { it.value to it.secondaryValue }
            _mainPageMetrics.update { it.copy(bloodPressure = v) }
        }
        launch {
            val v = HealthChartData.lastNightSleepMinutes(SLEEP_LOOKBACK, MINUTES_PER_HOUR)
            _mainPageMetrics.update { it.copy(sleepMinutes = v) }
        }
    }

    /** Body page point-in-time metrics: height, weight and body composition. */
    private suspend fun CoroutineScope.loadBodyMetrics() {
        launch {
            val v = HealthAPI.lastRecord(RecordType.Height)?.value
            _mainPageMetrics.update { it.copy(height = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.Weight)?.value
            _mainPageMetrics.update { it.copy(weight = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.BodyFat)?.value
            _mainPageMetrics.update { it.copy(bodyFat = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.BoneMass)?.value
            _mainPageMetrics.update { it.copy(boneMass = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.LeanBodyMass)?.value
            _mainPageMetrics.update { it.copy(leanBodyMass = v) }
        }
        launch {
            val v = HealthAPI.lastRecord(RecordType.BodyWaterMass)?.value
            _mainPageMetrics.update { it.copy(bodyWaterMass = v) }
        }
    }

    // ============================================================================================
    //  Bar / line chart aggregated data.
    // ============================================================================================

    private val _barChartData = MutableStateFlow(MetricDashboardData())
    val barChartData: StateFlow<MetricDashboardData> = _barChartData.asStateFlow()

    fun loadBarChartData(config: HealthMetricConfig, anchorDate: LocalDate, selectedTab: Int) {
        // Default dispatcher: the DAO calls suspend onto Room's own pool, and the
        // sortedBy/groupBy/Map chains in getListOfAverages/getListOfSums run on
        // the launching coroutine. Without this, those CPU-bound chains run on
        // Dispatchers.Main (the default for viewModelScope) and stall the frame.
        viewModelScope.launch(Dispatchers.Default) {
            val tz = TimeZone.currentSystemDefault()
            val resources = getApplication<Application>().resources

            val (startDate, endDate, periodType, periodType2) =
                HealthChartData.resolveChartRange(anchorDate, selectedTab)
            val startTime = startDate.atStartOfDayIn(tz)
            val endTime = endDate.atStartOfDayIn(tz)
            val endTimeNow = if (Clock.System.now() < endTime) Clock.System.now() else endTime

            val (rawPairs, rawPairsHistory) = HealthChartData.queryChartPairs(
                config, startTime, endTimeNow, periodType, periodType2,
            )
            val (mappedChart, mappedSecondaryChart) = HealthChartData.mapChartSeries(
                config, selectedTab, startDate, rawPairs, is24Hour(getApplication()),
            )
            val history = HealthChartData.buildHistoryItems(
                config, selectedTab, startDate, resources, rawPairsHistory,
            )

            _barChartData.value = HealthChartData.buildDashboardData(
                config, selectedTab, rawPairs, mappedChart, mappedSecondaryChart, history,
            )
        }
    }

    // ============================================================================================
    //  Nutrition / hydration logging.
    // ============================================================================================

    fun deleteRecord(record: Record) {
        viewModelScope.launch { HealthAPI.deleteRecord(record) }
    }

    fun logHydration(liters: Double, time: java.time.Instant) {
        viewModelScope.launch {
            val record = Record(
                id = Uuid.random().toString(),
                index = 0,
                type = RecordType.Hydration,
                startTime = time,
                endTime = time,
                value = liters,
                metadata = "Hydration",
            )
            repository.upsert(listOf(record))
            HealthAPI.writeHealthRecord(record)
        }
    }

    fun logBodyMetric(type: RecordType, value: Double, time: java.time.Instant) {
        viewModelScope.launch {
            val record = Record(
                id = Uuid.random().toString(),
                index = 0,
                type = type,
                startTime = time,
                endTime = time,
                value = value,
                metadata = type.name,
            )
            repository.upsert(listOf(record))
            HealthAPI.writeHealthRecord(record)
            loadMainPageMetrics()
        }
    }

    /** Type representing log meal target. */
    sealed class LogMealTarget {
        /** Immutable value describing from recipe; carries recipeId, name. */
        data class FromRecipe(val recipeId: String, val name: String) : LogMealTarget()
        /** Immutable value describing from ingredient; carries ingredient. */
        data class FromIngredient(val ingredient: Ingredient) : LogMealTarget()
    }

    fun logMeal(target: LogMealTarget, quantity: Double, time: java.time.Instant) {
        viewModelScope.launch {
            val nutrition: NutritionData = when (target) {
                is LogMealTarget.FromRecipe ->
                    HealthChartData.computeRecipeNutrition(repository, target.recipeId, quantity)
                is LogMealTarget.FromIngredient -> {
                    val ing = target.ingredient
                    NutritionData(
                        protein = ing.nutritionData.protein * quantity,
                        carbohydrates = ing.nutritionData.carbohydrates * quantity,
                        fat = ing.nutritionData.fat * quantity,
                        fiber = ing.nutritionData.fiber * quantity,
                        sugar = ing.nutritionData.sugar * quantity,
                        sodium = ing.nutritionData.sodium * quantity,
                        calories = ing.nutritionData.calories * quantity,
                    )
                }
            }
            val displayName = when (target) {
                is LogMealTarget.FromRecipe -> target.name
                is LogMealTarget.FromIngredient -> target.ingredient.displayName
            }
            val record = Record(
                id = Uuid.random().toString(),
                index = 0,
                type = RecordType.Nutrition,
                startTime = time,
                endTime = time,
                value = nutrition.calories,
                nutritionData = nutrition,
                metadata = displayName,
            )
            repository.upsert(listOf(record))
            HealthAPI.writeHealthRecord(record)
        }
    }

    // ============================================================================================
    //  Recipe / Ingredient CRUD.
    // ============================================================================================

    fun insertIngredient(ingredient: Ingredient) {
        viewModelScope.launch { repository.insertIngredient(ingredient) }
    }

    fun updateIngredient(ingredient: Ingredient) {
        viewModelScope.launch { repository.updateIngredient(ingredient) }
    }

    fun deleteIngredient(ingredient: Ingredient) {
        viewModelScope.launch {
            try {
                repository.deleteIngredient(ingredient)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                Log.status(TAG, "Failed to delete ingredient ${ingredient.id}: ${e.message}")
            }
        }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { repository.deleteRecipe(recipe) }
    }

    suspend fun getUnitsForIngredient(ingredientId: String): List<ServingUnit> =
        withContext(Dispatchers.IO) { repository.getUnitsForIngredient(ingredientId) }

    /** Loaded recipe + its resolved ingredient rows. */
    data class RecipeEditLoad(
        val name: String,
        val ingredients: List<RecipeIngredientLoad>,
    )

    /** A row in the editor: the ingredient, its serving unit, and the quantity. */
    data class RecipeIngredientLoad(
        val ingredient: Ingredient,
        val unit: ServingUnit,
        val quantity: Double,
    )

    suspend fun loadRecipeForEdit(recipeId: String): RecipeEditLoad? = withContext(Dispatchers.IO) {
        val recipe = repository.getRecipe(recipeId) ?: return@withContext null
        val ingredients = repository.getIngredientsForRecipe(recipeId)
        val rows = ingredients.mapNotNull { ri ->
            val ing = repository.getIngredient(ri.ingredientId)
            val units = repository.getUnitsForIngredient(ri.ingredientId)
            val unit = units.find { it.id == ri.unitId }
            if (ing != null && unit != null) RecipeIngredientLoad(ing, unit, ri.quantity) else null
        }
        RecipeEditLoad(recipe.name, rows)
    }

    fun saveRecipe(
        existingRecipeId: String?,
        name: String,
        items: List<RecipeIngredientLoad>,
        onComplete: () -> Unit,
    ) {
        viewModelScope.launch {
            val id = existingRecipeId ?: Uuid.random().toString()
            repository.insertRecipe(Recipe(id = id, name = name))

            if (existingRecipeId != null) {
                val oldIngredients = repository.getIngredientsForRecipe(existingRecipeId)
                oldIngredients.forEach { repository.deleteRecipeIngredient(it) }
            }

            items.forEach { row ->
                repository.insertIngredient(row.ingredient)
                repository.insertServingUnit(row.unit)
                repository.insertRecipeIngredient(
                    RecipeIngredient(
                        id = Uuid.random().toString(),
                        recipeId = id,
                        ingredientId = row.ingredient.id,
                        quantity = row.quantity,
                        unitId = row.unit.id,
                    ),
                )
            }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /** Result of an ingredient search dialog query. */
    data class IngredientSearchResults(
        val remote: List<FoodSearchAPI.SearchResult>,
        val local: List<Ingredient>,
    )

    suspend fun searchIngredients(query: String, includeLocal: Boolean): IngredientSearchResults =
        withContext(Dispatchers.IO) {
            val remote = FoodSearchAPI.searchIngredients(query)
            val local = if (includeLocal) repository.searchIngredients(query) else emptyList()
            IngredientSearchResults(remote, local)
        }

    // --- Food database -----------------------------------------------------

    /** Whether the bundled nutrition database has been unpacked yet. */
    val foodDatabaseStatus: StateFlow<FoodDatabase.Status> = FoodDatabase.status

    /**
     * Expand the food database shipped in the APK, if that hasn't happened
     * yet. Safe to call whenever a screen needing ingredient search appears -
     * it returns immediately once unpacked.
     *
     * The job is held here rather than in a composable so leaving the screen
     * mid-expansion doesn't cancel it and force the work to start over.
     */
    private var foodDatabaseJob: Job? = null

    fun prepareFoodDatabase() {
        if (foodDatabaseJob?.isActive == true) return
        foodDatabaseJob = viewModelScope.launch { FoodDatabase.prepare() }
    }

    /** Shared constants and helpers for HealthViewModel. */
    companion object {
        private const val TAG = "HealthViewModel"

        /** How far back a sleep record may end and still count as "last night". */
        private val SLEEP_LOOKBACK = java.time.Duration.ofHours(12)

        /** Sleep is stored in hours; the dashboard shows minutes. */
        private const val MINUTES_PER_HOUR = 60L

        /**
         * Week-tab slot fill: returns all 7 Sunday..Saturday days of the week
         * containing [weekStart], each paired with its value (or null when that
         * day has no records). DAO rows are keyed by epoch day.
         */
        internal fun fillWeekSeries(
            weekStart: LocalDate,
            valuesByEpochDay: Map<Long, Pair<Double?, Double?>>,
        ): List<Triple<LocalDate, Double?, Double?>> =
            (0 until HealthChartData.DAYS_PER_WEEK).map { offset ->
                val date = weekStart.plus(offset, DateTimeUnit.DAY)
                val pair = valuesByEpochDay[date.toEpochDays().toLong()]
                Triple(date, pair?.first, pair?.second)
            }
    }
}

/** Creates health view model instances. */
class HealthViewModelFactory(
    private val application: Application,
    private val repository: HealthRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HealthViewModel::class.java))
        return HealthViewModel(application, repository) as T
    }
}

/** Point-in-time metric bundle for the Home screen. */
data class MainPageMetrics(
    val br: Double? = null,
    val spo2: Double? = null,
    val hrv: Double? = null,
    val rhr: Long? = null,
    val skinTemp: Double? = null,
    val vo2Max: Double? = null,
    val bloodGlucose: Double? = null,
    val bloodPressure: Pair<Double, Double>? = null,
    val sleepMinutes: Long? = null,
    val height: Double? = null,
    val weight: Double? = null,
    val bodyFat: Double? = null,
    val boneMass: Double? = null,
    val leanBodyMass: Double? = null,
    val bodyWaterMass: Double? = null,
)
