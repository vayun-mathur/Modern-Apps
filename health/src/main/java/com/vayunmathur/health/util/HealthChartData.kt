package com.vayunmathur.health.util

import android.content.res.Resources
import com.vayunmathur.health.R
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.HistoryItem
import com.vayunmathur.health.ui.MetricDashboardData
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.Tuple3
import com.vayunmathur.library.util.Tuple4
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Pure chart/nutrition math behind [HealthViewModel]'s dashboard.
 *
 * Split out so the ViewModel stays a thin orchestration layer over repository flows:
 * everything here is a deterministic function of its arguments with no Android
 * framework access except formatted strings, which callers pass in.
 */
internal object HealthChartData {

    /** Sunday..Saturday slot count for the week tab. */
    internal const val DAYS_PER_WEEK = 7

    /** Hours shown per day-tab slot key; labels land on quarter-days. */
    private const val HOURS_PER_DAY = 24
    private const val LABEL_EVERY_HOURS = 6

    /** Month-tab day labels land on the 1st, 8th, 15th, 22nd and 29th. */
    private const val LABEL_EVERY_DAYS = 7

    /** Width of a chart range whose values are all equal, so the axis has span. */
    private const val EQUAL_RANGE_WIDTH = 1.0

    /** Nutrition facts are quoted per this many grams. */
    private const val GRAMS_PER_100G = 100.0

    /** Date range and aggregation periods for each chart tab. */
    internal fun resolveChartRange(
        anchorDate: LocalDate,
        selectedTab: Int,
    ): Tuple4<LocalDate, LocalDate, HealthAPI.PeriodType, HealthAPI.PeriodType> =
        when (selectedTab) {
            0 -> Tuple4(
                anchorDate,
                anchorDate.plus(1, DateTimeUnit.DAY),
                HealthAPI.PeriodType.Hourly,
                HealthAPI.PeriodType.Hourly,
            )
            1 -> {
                val start = anchorDate.minus(
                    (anchorDate.dayOfWeek.ordinal + 1) % DAYS_PER_WEEK,
                    DateTimeUnit.DAY,
                )
                Tuple4(
                    start,
                    start.plus(DAYS_PER_WEEK, DateTimeUnit.DAY),
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

    internal suspend fun queryChartPairs(
        config: HealthMetricConfig,
        startTime: kotlin.time.Instant,
        endTimeNow: kotlin.time.Instant,
        periodType: HealthAPI.PeriodType,
        periodType2: HealthAPI.PeriodType,
    ): Pair<List<Tuple3<Long, Double, Double>>, List<Tuple3<Long, Double, Double>>> {
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
        // Height is stored in meters but its config/goal/unit are centimeters
        // (175 cm); every other metric stores in its display unit already.
        return scaleForDisplay(config, rawPairs) to scaleForDisplay(config, rawPairsHistory)
    }

    /** Converts stored values to the metric's display unit (height m -> cm only). */
    internal fun scaleForDisplay(
        config: HealthMetricConfig,
        pairs: List<Tuple3<Long, Double, Double>>,
    ): List<Tuple3<Long, Double, Double>> {
        if (config.recordType != com.vayunmathur.health.data.RecordType.Height) return pairs
        return pairs.map { Tuple3(it.first, it.second * 100.0, it.third * 100.0) }
    }

    /**
     * Chart series with one slot per weekday on the week tab.
     *
     * The DAO only returns days that have records, so mapping it directly collapses
     * the series left: Tuesday's bar lands in Sunday's slot (#727). Missing days become
     * null (bar chart renders zero, line chart a gap) so each value keeps its own slot.
     */
    internal fun mapChartSeries(
        config: HealthMetricConfig,
        selectedTab: Int,
        startDate: LocalDate,
        rawPairs: List<Tuple3<Long, Double, Double>>,
        use24Hour: Boolean,
    ): Pair<List<Pair<String, Double?>>, List<Pair<String, Double?>>?> {
        // Week tab must always show 7 Sunday..Saturday slots.
        val weekSeries = if (selectedTab == 1) {
            HealthViewModel.fillWeekSeries(
                startDate,
                rawPairs.associate { it.first to (it.second to it.third) },
            )
        } else {
            null
        }
        val mappedChart: List<Pair<String, Double?>> = weekSeries?.map { (date, value, _) ->
            labelFor(selectedTab, date.toEpochDays().toLong(), use24Hour) to value
        } ?: rawPairs.map { p ->
            labelFor(selectedTab, p.first, use24Hour) to p.second
        }
        val mappedSecondaryChart: List<Pair<String, Double?>>? = if (config.isDualSeries) {
            weekSeries?.map { (date, _, secondary) ->
                labelFor(selectedTab, date.toEpochDays().toLong(), use24Hour) to secondary
            } ?: rawPairs.map { p -> labelFor(selectedTab, p.first, use24Hour) to p.third }
        } else {
            null
        }
        return mappedChart to mappedSecondaryChart
    }

    /**
     * History rows below the chart, labelled by each row's own date rather than position.
     *
     * Bar metrics show all 7 days (missing = 0); line metrics list only days with readings
     * so gaps don't drag the average to zero.
     */
    internal fun buildHistoryItems(
        config: HealthMetricConfig,
        selectedTab: Int,
        startDate: LocalDate,
        startTime: kotlin.time.Instant,
        tz: TimeZone,
        resources: Resources,
        rawPairsHistory: List<Tuple3<Long, Double, Double>>,
    ): List<HistoryItem> {
        val weekHistorySeries = if (selectedTab == 1) {
            HealthViewModel.fillWeekSeries(
                startDate,
                rawPairsHistory.associate { it.first to (it.second to it.third) },
            )
        } else {
            null
        }
        return if (selectedTab == 1 && weekHistorySeries != null) {
            weekHistorySeries.mapNotNull { (date, value, secondary) ->
                if (value == null && config.isLineChart) return@mapNotNull null
                val label = localizedDayOfWeekNames(DateNameStyle.FULL)[
                    date.dayOfWeek.isoDayNumber - 1
                ]
                HistoryItem(
                    label = label,
                    value = value ?: 0.0,
                    secondaryValue = if (config.isDualSeries) secondary else null,
                    unit = config.unit,
                    isGoalMet = (value ?: 0.0) >= config.dailyGoal,
                    useDecimals = config.useDecimals,
                )
            }.reversed()
        } else if (selectedTab != 0) {
            // Label from the row's own bucket key, not its position: DAO rows
            // only exist for periods with data, so position mislabels sparse
            // months/years (same class as the fixed week bug #727).
            rawPairsHistory.map { triple ->
                HistoryItem(
                    label = historyLabelForKey(selectedTab, triple.first, resources),
                    value = triple.second,
                    secondaryValue = if (config.isDualSeries) triple.third else null,
                    unit = config.unit,
                    isGoalMet = triple.second >= config.dailyGoal,
                    useDecimals = config.useDecimals,
                )
            }.reversed()
        } else {
            listOf()
        }
    }

    /** The calendar date a bucket key names (pure; label rendering needs Resources). */
    internal fun bucketDate(epochDay: Long): LocalDate = LocalDate.fromEpochDays(epochDay.toInt())

    /** History label from a bucket's epoch-day key (week/month start for tabs 2/3). */
    internal fun historyLabelForKey(
        selectedTab: Int,
        epochDay: Long,
        resources: Resources,
    ): String {
        val date = bucketDate(epochDay)
        return when (selectedTab) {
            2 -> resources.getString(
                R.string.month_year_format,
                localizedMonthNames(DateNameStyle.SHORT)[date.month.number - 1],
                date.day,
            )
            else -> localizedMonthNames(DateNameStyle.FULL)[date.month.number - 1]
        }
    }

    internal fun buildDashboardData(
        config: HealthMetricConfig,
        selectedTab: Int,
        rawPairs: List<Tuple3<Long, Double, Double>>,
        mappedChart: List<Pair<String, Double?>>,
        mappedSecondaryChart: List<Pair<String, Double?>>?,
        history: List<HistoryItem>,
    ): MetricDashboardData {
        val nonNullPrimary =
            if (selectedTab == 0) rawPairs.map { it.second } else history.map { it.value }
        val nonNullSecondary = if (selectedTab == 0) {
            if (config.isDualSeries) rawPairs.map { it.third } else emptyList()
        } else {
            if (config.isDualSeries) history.mapNotNull { it.secondaryValue } else emptyList()
        }
        return MetricDashboardData(
            totalValue = nonNullPrimary.sum(),
            dailyAverage = if (nonNullPrimary.isEmpty()) 0.0
            else (if (selectedTab == 0) nonNullPrimary.sum() else nonNullPrimary.average()),
            secondaryAverage = if (nonNullSecondary.isEmpty()) null
            else (if (selectedTab == 0) nonNullSecondary.sum() else nonNullSecondary.average()),
            chartData = mappedChart,
            secondaryChartData = mappedSecondaryChart,
            historyItems = history,
            // Fixed 7 for the week tab: spacing math must divide by all 7
            // slots even when only some days have data.
            totalBarCount = if (selectedTab == 1) DAYS_PER_WEEK else rawPairs.size,
            primaryRange = chartRange(mappedChart),
        )
    }

    /** Min..max of the plotted values, widened when every value is equal. */
    private fun chartRange(mappedChart: List<Pair<String, Double?>>): ClosedFloatingPointRange<Double>? {
        val vals = mappedChart.mapNotNull { it.second }
        if (vals.isEmpty()) return null
        val min = vals.minOrNull() ?: return null
        val max = vals.maxOrNull() ?: return null
        if (min < max) return min..max
        if (min > max) return max..min
        return min..min + EQUAL_RANGE_WIDTH
    }

    private fun labelFor(
        selectedTab: Int,
        firstKey: Long,
        use24Hour: Boolean,
    ): String = when (selectedTab) {
        0 -> {
            val hour = (firstKey % HOURS_PER_DAY).toInt()
            if (hour % LABEL_EVERY_HOURS == 0) {
                DateString.hourLabel(hour, use24Hour)
            } else {
                ""
            }
        }
        1 -> {
            val date = LocalDate.fromEpochDays(firstKey.toInt())
            localizedDayOfWeekNames(DateNameStyle.SHORT)[date.dayOfWeek.isoDayNumber - 1]
        }
        2 -> {
            val date = LocalDate.fromEpochDays(firstKey.toInt())
            if (date.day % LABEL_EVERY_DAYS == 1) date.day.toString() else ""
        }
        else -> {
            val date = LocalDate.fromEpochDays(firstKey.toInt())
            localizedMonthNames(DateNameStyle.SHORT)[date.month.number - 1]
        }
    }

    /** Last night's sleep in minutes, or null when the latest record is older than that. */
    internal suspend fun lastNightSleepMinutes(
        lookback: java.time.Duration,
        hoursToMinutes: Long,
    ): Long? =
        HealthAPI.lastRecord(RecordType.Sleep)?.let { record ->
            val todayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
            if (record.endTime.isAfter(todayStart.minus(lookback))) {
                (record.value * hoursToMinutes).toLong()
            } else {
                null
            }
        }

    /** Scales per-100g ingredient nutrition to the grams used in a recipe. */
    internal suspend fun computeRecipeNutrition(
        repository: HealthRepository,
        recipeId: String,
        quantity: Double,
    ): NutritionData {
        val ingredients = repository.getIngredientsForRecipe(recipeId)
        var protein = 0.0
        var carbs = 0.0
        var fat = 0.0
        var fiber = 0.0
        var sugar = 0.0
        var sodium = 0.0
        var kcal = 0.0

        ingredients.forEach { ri ->
            val ing = repository.getIngredient(ri.ingredientId) ?: return@forEach
            val units = repository.getUnitsForIngredient(ing.id)
            val unit = units.find { it.id == ri.unitId }
            val grams = unit?.grams ?: 1.0
            val totalGrams = ri.quantity * grams * quantity
            // Nutrition facts are per 100g; scale to the actual grams in the recipe.
            protein += (ing.nutritionData.protein / GRAMS_PER_100G) * totalGrams
            carbs += (ing.nutritionData.carbohydrates / GRAMS_PER_100G) * totalGrams
            fat += (ing.nutritionData.fat / GRAMS_PER_100G) * totalGrams
            fiber += (ing.nutritionData.fiber / GRAMS_PER_100G) * totalGrams
            sugar += (ing.nutritionData.sugar / GRAMS_PER_100G) * totalGrams
            sodium += (ing.nutritionData.sodium / GRAMS_PER_100G) * totalGrams
            kcal += (ing.nutritionData.calories / GRAMS_PER_100G) * totalGrams
        }
        return NutritionData(protein, carbs, fat, fiber, sugar, sodium, calories = kcal)
    }
}
