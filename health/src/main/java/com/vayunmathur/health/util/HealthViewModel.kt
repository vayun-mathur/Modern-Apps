package com.vayunmathur.health.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ResultReceiver
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.MedicalResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.fhir.model.r4b.Immunization
import com.google.fhir.model.r4b.Observation
import com.vayunmathur.health.R
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecipeIngredient
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.HistoryItem
import com.vayunmathur.health.ui.JSON
import com.vayunmathur.health.ui.MetricDashboardData
import com.vayunmathur.library.util.Tuple4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Clock

/**
 * ViewModel for the Health app.
 *
 * Owns:
 *  - All HealthConnect / Room health-record queries previously called from composables
 *    (point-in-time metrics for the main page, bar/line chart aggregations).
 *  - Medical record (Immunization / Observation) reads + FHIR JSON parse.
 *  - Nutrition / hydration logging writes (Room + HealthConnect insert).
 *  - Recipe / Ingredient CRUD (Room).
 *  - PDF → image conversion + InferenceService dispatch for the OpenAssistant extraction flow.
 *
 * Composables should:
 *  - Use `viewModel.<metric>InRange(type, start, end).collectAsState(0.0)` for flow-based reads.
 *  - Call `viewModel.loadMainPageMetrics()`, `viewModel.loadBarChartData(...)`,
 *    `viewModel.refreshImmunizations()`, etc. from a single `LaunchedEffect` and
 *    collect the resulting `StateFlow`.
 *  - Keep purely-UI state (dialog visibility, pager state, focus, text-field cursor) in compose.
 */
class HealthViewModel(application: Application) : AndroidViewModel(application) {

    private val db get() = HealthAPI.db

    // ============================================================================================
    //  Flow getters — direct passthrough to the DAO so callers can `collectAsState(...)`.
    //  Composables wrap them in `remember(...)` when the keys depend on changing values to avoid
    //  re-subscribing every recomposition.
    // ============================================================================================

    fun sumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumInRange(type, start, end)

    fun sumProteinInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumProteinInRange(type, start, end)

    fun sumCarbsInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumCarbsInRange(type, start, end)

    fun sumFatInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumFatInRange(type, start, end)

    fun sumFiberInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumFiberInRange(type, start, end)

    fun sumSugarInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumSugarInRange(type, start, end)

    fun sumSodiumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumSodiumInRange(type, start, end)

    fun sumBiotinInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumBiotinInRange(type, start, end)

    fun sumCaffeineInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumCaffeineInRange(type, start, end)

    fun sumCalciumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumCalciumInRange(type, start, end)

    fun sumChlorideInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumChlorideInRange(type, start, end)

    fun sumCholesterolInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumCholesterolInRange(type, start, end)

    fun sumChromiumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumChromiumInRange(type, start, end)

    fun sumCopperInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumCopperInRange(type, start, end)

    fun sumFolateInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumFolateInRange(type, start, end)

    fun sumFolicAcidInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumFolicAcidInRange(type, start, end)

    fun sumIodineInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumIodineInRange(type, start, end)

    fun sumIronInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumIronInRange(type, start, end)

    fun sumMagnesiumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumMagnesiumInRange(type, start, end)

    fun sumManganeseInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumManganeseInRange(type, start, end)

    fun sumMolybdenumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumMolybdenumInRange(type, start, end)

    fun sumNiacinInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumNiacinInRange(type, start, end)

    fun sumPantothenicAcidInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumPantothenicAcidInRange(type, start, end)

    fun sumPhosphorusInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumPhosphorusInRange(type, start, end)

    fun sumPotassiumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumPotassiumInRange(type, start, end)

    fun sumRiboflavinInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumRiboflavinInRange(type, start, end)

    fun sumSaturatedFatInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumSaturatedFatInRange(type, start, end)

    fun sumSeleniumInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumSeleniumInRange(type, start, end)

    fun sumThiaminInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumThiaminInRange(type, start, end)

    fun sumTransFatInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumTransFatInRange(type, start, end)

    fun sumVitaminAInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumVitaminAInRange(type, start, end)

    fun sumVitaminB12InRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumVitaminB12InRange(type, start, end)

    fun sumVitaminB6InRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumVitaminB6InRange(type, start, end)

    fun sumVitaminCInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumVitaminCInRange(type, start, end)

    fun sumVitaminDInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumVitaminDInRange(type, start, end)

    fun sumVitaminEInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumVitaminEInRange(type, start, end)

    fun sumVitaminKInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumVitaminKInRange(type, start, end)

    fun sumZincInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double> =
        db.healthDao().sumZincInRange(type, start, end)

    fun maxInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double?> =
        db.healthDao().maxInRange(type, start, end)

    fun minInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<Double?> =
        db.healthDao().minInRange(type, start, end)

    fun getAllRecordsInRange(type: RecordType, start: kotlin.time.Instant, end: kotlin.time.Instant): Flow<List<Record>> =
        db.healthDao().getAllInRange(type, start, end)

    // ============================================================================================
    //  Recipe / Ingredient flows.
    // ============================================================================================

    val allRecipes: Flow<List<Recipe>> get() = db.healthDao().getAllRecipesFlow()
    val allIngredients: Flow<List<Ingredient>> get() = db.healthDao().getAllIngredientsFlow()
    val ingredientsAsRecipes: Flow<List<Ingredient>> get() = db.healthDao().getIngredientsAsRecipesFlow()

    // ============================================================================================
    //  Main page point-in-time metric bundle.
    // ============================================================================================

    private val _mainPageMetrics = MutableStateFlow(MainPageMetrics())
    val mainPageMetrics: StateFlow<MainPageMetrics> = _mainPageMetrics.asStateFlow()

    fun loadMainPageMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            coroutineScope {
                val spo2D = async { HealthAPI.lastRecord(RecordType.OxygenSaturation)?.value }
                val brD = async { HealthAPI.lastRecord(RecordType.RespiratoryRate)?.value }
                val hrvD = async { HealthAPI.lastRecord(RecordType.HeartRateVariabilityRmssd)?.value }
                val rhrD = async { HealthAPI.lastRecord(RecordType.RestingHeartRate)?.value?.toLong() }
                val skinTempD = async { HealthAPI.lastRecord(RecordType.SkinTemperature)?.value }
                val vo2MaxD = async { HealthAPI.lastRecord(RecordType.Vo2Max)?.value }
                val bloodGlucoseD = async { HealthAPI.lastRecord(RecordType.BloodGlucose)?.value }
                val bloodPressureD = async {
                    HealthAPI.lastRecord(RecordType.BloodPressure)?.let { it.value to it.secondaryValue }
                }
                val sleepD = async {
                    HealthAPI.lastRecord(RecordType.Sleep)?.let { record ->
                        val todayStart = java.time.LocalDate.now()
                            .atStartOfDay(ZoneId.systemDefault()).toInstant()
                        if (record.endTime.isAfter(todayStart.minus(java.time.Duration.ofHours(12)))) {
                            (record.value * 60).toLong()
                        } else null
                    }
                }
                val heightD = async { HealthAPI.lastRecord(RecordType.Height)?.value }
                val weightD = async { HealthAPI.lastRecord(RecordType.Weight)?.value }
                val bodyFatD = async { HealthAPI.lastRecord(RecordType.BodyFat)?.value }
                val boneMassD = async { HealthAPI.lastRecord(RecordType.BoneMass)?.value }
                val leanBodyMassD = async { HealthAPI.lastRecord(RecordType.LeanBodyMass)?.value }
                val bodyWaterMassD = async { HealthAPI.lastRecord(RecordType.BodyWaterMass)?.value }

                _mainPageMetrics.value = MainPageMetrics(
                    br = brD.await(),
                    spo2 = spo2D.await(),
                    hrv = hrvD.await(),
                    rhr = rhrD.await(),
                    skinTemp = skinTempD.await(),
                    vo2Max = vo2MaxD.await(),
                    bloodGlucose = bloodGlucoseD.await(),
                    bloodPressure = bloodPressureD.await(),
                    sleepMinutes = sleepD.await(),
                    height = heightD.await(),
                    weight = weightD.await(),
                    bodyFat = bodyFatD.await(),
                    boneMass = boneMassD.await(),
                    leanBodyMass = leanBodyMassD.await(),
                    bodyWaterMass = bodyWaterMassD.await(),
                )
            }
        }
    }

    // ============================================================================================
    //  Bar / line chart aggregated data.
    // ============================================================================================

    private val _barChartData = MutableStateFlow(MetricDashboardData())
    val barChartData: StateFlow<MetricDashboardData> = _barChartData.asStateFlow()

    fun loadBarChartData(config: HealthMetricConfig, anchorDate: LocalDate, selectedTab: Int) {
        viewModelScope.launch {
            val tz = TimeZone.currentSystemDefault()
            val resources = getApplication<Application>().resources

            val (startDate, endDate, periodType, periodType2) = when (selectedTab) {
                0 -> Tuple4(
                    anchorDate,
                    anchorDate.plus(1, DateTimeUnit.DAY),
                    HealthAPI.PeriodType.Hourly,
                    HealthAPI.PeriodType.Hourly,
                )
                1 -> {
                    val start = anchorDate.minus((anchorDate.dayOfWeek.ordinal + 1) % 7, DateTimeUnit.DAY)
                    Tuple4(
                        start,
                        start.plus(7, DateTimeUnit.DAY),
                        HealthAPI.PeriodType.Daily,
                        HealthAPI.PeriodType.Daily,
                    )
                }
                2 -> {
                    val start = LocalDate(anchorDate.year, anchorDate.month, 1)
                    val end = start.plus(1, DateTimeUnit.MONTH)
                    Tuple4(start, end, HealthAPI.PeriodType.Daily, HealthAPI.PeriodType.Weekly)
                }
                else -> {
                    val start = LocalDate(anchorDate.year, 1, 1)
                    val end = start.plus(1, DateTimeUnit.YEAR)
                    Tuple4(start, end, HealthAPI.PeriodType.Monthly, HealthAPI.PeriodType.Monthly)
                }
            }
            val startTime = startDate.atStartOfDayIn(tz)
            val endTime = endDate.atStartOfDayIn(tz)
            val endTimeNow = if (Clock.System.now() < endTime) Clock.System.now() else endTime

            val rawPairs = if (config.isLineChart) {
                HealthAPI.getListOfAverages(config.recordType, startTime, endTimeNow, periodType)
            } else {
                HealthAPI.getListOfSums(config.recordType, startTime, endTimeNow, periodType)
            }
            val rawPairsHistory = if (config.isLineChart) {
                HealthAPI.getListOfAverages(config.recordType, startTime, endTimeNow, periodType2)
            } else {
                HealthAPI.getListOfSums(config.recordType, startTime, endTimeNow, periodType2)
            }

            val mappedChart = rawPairs.map { p ->
                labelFor(selectedTab, p.first, resources) to p.second
            }
            val mappedSecondaryChart = if (config.isDualSeries) {
                rawPairs.map { p -> labelFor(selectedTab, p.first, resources) to p.third }
            } else null

            val history = if (selectedTab != 0) rawPairsHistory.mapIndexed { index, triple ->
                val label = when (selectedTab) {
                    0 -> ""
                    1 -> startTime.plus(index.toLong(), DateTimeUnit.DAY, tz)
                        .toLocalDateTime(tz).dayOfWeek.name.lowercase()
                        .replaceFirstChar { it.uppercase() }
                    2 -> {
                        val date = startTime.plus(index.toLong(), DateTimeUnit.DAY, tz)
                            .toLocalDateTime(tz).date
                        resources.getString(
                            R.string.month_year_format,
                            date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                            date.day,
                        )
                    }
                    else -> {
                        val date = startTime.plus(index.toLong(), DateTimeUnit.MONTH, tz)
                            .toLocalDateTime(tz).date
                        date.month.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                }
                HistoryItem(
                    label = label,
                    value = triple.second,
                    secondaryValue = if (config.isDualSeries) triple.third else null,
                    unit = config.unit,
                    isGoalMet = triple.second >= config.dailyGoal,
                    useDecimals = config.useDecimals,
                )
            }.reversed() else listOf()

            val nonNullPrimary =
                if (selectedTab == 0) rawPairs.map { it.second } else history.map { it.value }
            val nonNullSecondary = if (selectedTab == 0) {
                if (config.isDualSeries) rawPairs.map { it.third } else emptyList()
            } else {
                if (config.isDualSeries) history.mapNotNull { it.secondaryValue } else emptyList()
            }

            _barChartData.value = MetricDashboardData(
                totalValue = nonNullPrimary.sum(),
                dailyAverage = if (nonNullPrimary.isEmpty()) 0.0
                else (if (selectedTab == 0) nonNullPrimary.sum() else nonNullPrimary.average()),
                secondaryAverage = if (nonNullSecondary.isEmpty()) null
                else (if (selectedTab == 0) nonNullSecondary.sum() else nonNullSecondary.average()),
                chartData = mappedChart,
                secondaryChartData = mappedSecondaryChart,
                historyItems = history,
                totalBarCount = rawPairs.size,
                primaryRange = mappedChart.mapNotNull { it.second }.let { vals ->
                    if (vals.isEmpty()) null
                    else vals.minOrNull()!!.let { min ->
                        vals.maxOrNull()!!.let { max ->
                            if (min < max) min..max else if (min > max) max..min else min..min + 1.0
                        }
                    }
                },
            )
        }
    }

    private fun labelFor(
        selectedTab: Int,
        firstKey: Long,
        resources: android.content.res.Resources,
    ): String = when (selectedTab) {
        0 -> {
            val hour = (firstKey % 24).toInt()
            if (hour % 6 == 0) {
                val amPm = if (hour < 12) "AM" else "PM"
                val h = if (hour % 12 == 0) 12 else hour % 12
                resources.getString(R.string.hour_am_pm_format, h, amPm)
            } else ""
        }
        1 -> {
            val date = LocalDate.fromEpochDays(firstKey.toInt())
            date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        }
        2 -> {
            val date = LocalDate.fromEpochDays(firstKey.toInt())
            if (date.day % 7 == 1) date.day.toString() else ""
        }
        else -> {
            val date = LocalDate.fromEpochDays(firstKey.toInt())
            date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    // ============================================================================================
    //  Medical records (Immunizations / Lab Results).
    // ============================================================================================

    private val _immunizations = MutableStateFlow<List<Immunization>>(emptyList())
    val immunizations: StateFlow<List<Immunization>> = _immunizations.asStateFlow()

    private val _labResults = MutableStateFlow<List<Observation>>(emptyList())
    val labResults: StateFlow<List<Observation>> = _labResults.asStateFlow()

    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    fun refreshImmunizations() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _immunizations.value = HealthAPI
                    .allMedicalRecords(MedicalResource.MEDICAL_RESOURCE_TYPE_VACCINES)
                    .map { JSON.decodeFromString<Immunization>(it.fhirResource.data) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh immunizations", e)
            }
        }
    }

    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    fun refreshLabResults() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _labResults.value = HealthAPI
                    .allMedicalRecords(MedicalResource.MEDICAL_RESOURCE_TYPE_LABORATORY_RESULTS)
                    .map { JSON.decodeFromString<Observation>(it.fhirResource.data) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh lab results", e)
            }
        }
    }

    fun writeImmunization(jsonResult: String) {
        viewModelScope.launch {
            try {
                HealthAPI.writeMedicalRecord(jsonResult)
                refreshImmunizations()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write immunization record", e)
            }
        }
    }

    fun writeLabResult(jsonResult: String) {
        viewModelScope.launch {
            try {
                HealthAPI.writeMedicalRecord(jsonResult)
                refreshLabResults()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write lab result record", e)
            }
        }
    }

    /**
     * Converts [uri] (a PDF) to per-page PNGs in the cache dir, then sends them to the
     * OpenAssistant InferenceService for extraction. The result is delivered via [receiver].
     * If the PDF cannot be opened or no pages render, [onFailedToStart] is invoked on the main
     * thread so the caller can clear any "processing" UI state.
     */
    fun extractMedicalDataFromPdf(
        uri: Uri,
        userText: String,
        schema: String,
        receiver: ResultReceiver,
        onFailedToStart: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                val imagePaths = convertPdfToImages(ctx, uri)
                if (imagePaths.isEmpty()) {
                    withContext(Dispatchers.Main) { onFailedToStart() }
                    return@launch
                }
                val intent = Intent().apply {
                    setClassName(
                        "com.vayunmathur.openassistant",
                        "com.vayunmathur.openassistant.util.InferenceService",
                    )
                    putExtra("user_text", userText)
                    val uris = imagePaths.map { path ->
                        val u = FileProvider.getUriForFile(
                            ctx,
                            "${ctx.packageName}.fileprovider",
                            File(path),
                        )
                        ctx.grantUriPermission(
                            "com.vayunmathur.openassistant",
                            u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        u
                    }
                    putParcelableArrayListExtra("image_uris", ArrayList(uris))
                    putExtra("schema", schema)
                    putExtra("RECEIVER", receiver)
                }
                withContext(Dispatchers.Main) { ctx.startService(intent) }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing PDF", e)
                withContext(Dispatchers.Main) { onFailedToStart() }
            }
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
                id = UUID.randomUUID().toString(),
                index = 0,
                type = RecordType.Hydration,
                startTime = time,
                endTime = time,
                value = liters,
                metadata = "Hydration",
            )
            db.healthDao().upsert(listOf(record))
            HealthAPI.writeHealthRecord(record)
        }
    }

    sealed class LogMealTarget {
        data class FromRecipe(val recipeId: String, val name: String) : LogMealTarget()
        data class FromIngredient(val ingredient: Ingredient) : LogMealTarget()
    }

    fun logMeal(target: LogMealTarget, quantity: Double, time: java.time.Instant) {
        viewModelScope.launch {
            val nutrition: NutritionData = when (target) {
                is LogMealTarget.FromRecipe -> computeRecipeNutrition(target.recipeId, quantity)
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
                id = UUID.randomUUID().toString(),
                index = 0,
                type = RecordType.Nutrition,
                startTime = time,
                endTime = time,
                value = nutrition.calories,
                nutritionData = nutrition,
                metadata = displayName,
            )
            db.healthDao().upsert(listOf(record))
            HealthAPI.writeHealthRecord(record)
        }
    }

    private suspend fun computeRecipeNutrition(recipeId: String, quantity: Double): NutritionData {
        val ingredients = db.healthDao().getIngredientsForRecipe(recipeId)
        var protein = 0.0
        var carbs = 0.0
        var fat = 0.0
        var fiber = 0.0
        var sugar = 0.0
        var sodium = 0.0
        var kcal = 0.0

        ingredients.forEach { ri ->
            val ing = db.healthDao().getIngredient(ri.ingredientId) ?: return@forEach
            val units = db.healthDao().getUnitsForIngredient(ing.id)
            val unit = units.find { it.id == ri.unitId }
            val grams = unit?.grams ?: 1.0
            val totalGrams = ri.quantity * grams * quantity
            protein += (ing.nutritionData.protein / 100.0) * totalGrams
            carbs += (ing.nutritionData.carbohydrates / 100.0) * totalGrams
            fat += (ing.nutritionData.fat / 100.0) * totalGrams
            fiber += (ing.nutritionData.fiber / 100.0) * totalGrams
            sugar += (ing.nutritionData.sugar / 100.0) * totalGrams
            sodium += (ing.nutritionData.sodium / 100.0) * totalGrams
            kcal += (ing.nutritionData.calories / 100.0) * totalGrams
        }
        return NutritionData(protein, carbs, fat, fiber, sugar, sodium, calories = kcal)
    }

    // ============================================================================================
    //  Recipe / Ingredient CRUD.
    // ============================================================================================

    fun insertIngredient(ingredient: Ingredient) {
        viewModelScope.launch { db.healthDao().insertIngredient(ingredient) }
    }

    fun updateIngredient(ingredient: Ingredient) {
        viewModelScope.launch { db.healthDao().updateIngredient(ingredient) }
    }

    fun deleteIngredient(ingredient: Ingredient) {
        viewModelScope.launch {
            try {
                db.healthDao().deleteIngredient(ingredient)
            } catch (e: Exception) {
                // SQLiteConstraintException if used in a recipe — swallow as before.
            }
        }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { db.healthDao().deleteRecipe(recipe) }
    }

    suspend fun getUnitsForIngredient(ingredientId: String): List<ServingUnit> =
        withContext(Dispatchers.IO) { db.healthDao().getUnitsForIngredient(ingredientId) }

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
        val recipe = db.healthDao().getRecipe(recipeId) ?: return@withContext null
        val ingredients = db.healthDao().getIngredientsForRecipe(recipeId)
        val rows = ingredients.mapNotNull { ri ->
            val ing = db.healthDao().getIngredient(ri.ingredientId)
            val units = db.healthDao().getUnitsForIngredient(ri.ingredientId)
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
            val id = existingRecipeId ?: UUID.randomUUID().toString()
            db.healthDao().insertRecipe(Recipe(id = id, name = name))

            if (existingRecipeId != null) {
                val oldIngredients = db.healthDao().getIngredientsForRecipe(existingRecipeId)
                oldIngredients.forEach { db.healthDao().deleteRecipeIngredient(it) }
            }

            items.forEach { row ->
                db.healthDao().insertIngredient(row.ingredient)
                db.healthDao().insertServingUnit(row.unit)
                db.healthDao().insertRecipeIngredient(
                    RecipeIngredient(
                        id = UUID.randomUUID().toString(),
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
            val local = if (includeLocal) db.healthDao().searchIngredients(query) else emptyList()
            IngredientSearchResults(remote, local)
        }

    companion object {
        private const val TAG = "HealthViewModel"
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

/** Internal PDF → image helper. Splits a multi-page PDF into per-page PNGs in the cache dir. */
private fun convertPdfToImages(context: Context, uri: Uri): List<String> {
    val imagePaths = mutableListOf<String>()
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val renderer = PdfRenderer(pfd)
            for (i in 0 until renderer.pageCount) {
                try {
                    val page = renderer.openPage(i)
                    val bitmap = createBitmap(page.width, page.height)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val file = File(context.cacheDir, "pdf_page_$i.png")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    page.close()
                    imagePaths.add(file.absolutePath)
                } catch (e: Exception) {
                    Log.e("HealthViewModel", "Error rendering PDF page $i", e)
                }
            }
            renderer.close()
        }
    } catch (e: Exception) {
        Log.e("HealthViewModel", "Error opening PDF file descriptor for URI: $uri", e)
    }
    return imagePaths
}
