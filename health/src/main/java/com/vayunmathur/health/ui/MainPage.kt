package com.vayunmathur.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.NutritionRecord
import com.vayunmathur.health.util.MainPageMetrics
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.round
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

val JSON = kotlinx.serialization.json.Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

@OptIn(ExperimentalPersonalHealthRecordApi::class)
@Composable
fun MainPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = now.date

    val dayRange = remember(today) {
        val start = today.atStartOfDayIn(TimeZone.currentSystemDefault())
        start to start.plus(24.hours)
    }
    val dayStart = dayRange.first
    val dayEnd = dayRange.second

    val totalCaloriesBurnedToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.CaloriesTotal, dayStart, dayEnd).map { it.toLong() } }.collectAsState(0L)
    val activeCaloriesBurnedToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.CaloriesActive, dayStart, dayEnd).map { it.toLong() } }.collectAsState(0L)
    val basalCaloriesBurnedToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.CaloriesBasal, dayStart, dayEnd).map { it.toLong() } }.collectAsState(0L)
    val stepsToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.Steps, dayStart, dayEnd).map { it.toLong() } }.collectAsState(0L)
    val wheelchairPushesToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.Wheelchair, dayStart, dayEnd).map { it.toLong() } }.collectAsState(0L)
    val mindfulnessToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.Mindfulness, dayStart, dayEnd).map { it.toLong() } }.collectAsState(0L)
    val distanceToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.Distance, dayStart, dayEnd) }.collectAsState(0.0)
    val floorsClimbedToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.Floors, dayStart, dayEnd) }.collectAsState(0.0)
    val elevationGainedToday by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.Elevation, dayStart, dayEnd) }.collectAsState(0.0)

    val heartRateMaxToday by remember(dayStart, dayEnd) { viewModel.maxInRange(RecordType.HeartRate, dayStart, dayEnd).map { it?.toLong() ?: 0L } }.collectAsState(0L)
    val heartRateMinToday by remember(dayStart, dayEnd) { viewModel.minInRange(RecordType.HeartRate, dayStart, dayEnd).map { it?.toLong() ?: 0L } }.collectAsState(0L)

    val metrics: MainPageMetrics by viewModel.mainPageMetrics.collectAsState()
    val br = metrics.br
    val spo2 = metrics.spo2
    val hrv = metrics.hrv
    val rhr = metrics.rhr
    val skinTemp = metrics.skinTemp
    val vo2Max = metrics.vo2Max
    val bloodGlucose = metrics.bloodGlucose
    val bloodPressure = metrics.bloodPressure
    val sleepMinutes = metrics.sleepMinutes
    val height = metrics.height
    val weight = metrics.weight
    val bodyFat = metrics.bodyFat
    val boneMass = metrics.boneMass
    val leanBodyMass = metrics.leanBodyMass
    val bodyWaterMass = metrics.bodyWaterMass

    LaunchedEffect(today) {
        viewModel.loadMainPageMetrics()
    }

    Scaffold { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = paddingValues.calculateStartPadding(layoutDirection) + 16.dp,
                top = paddingValues.calculateTopPadding() + 8.dp,
                end = paddingValues.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 4. Activity & Energy
            item {
                Text(stringResource(R.string.section_activity), style = MaterialTheme.typography.labelLarge)
            }
            item {
                EnergyBurned(backStack, totalCaloriesBurnedToday)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        MiniMetricCard(stringResource(R.string.label_active), activeCaloriesBurnedToday.toString(), stringResource(R.string.unit_cal), onClick = {
                            backStack.add(Route.BarChartDetails(HealthMetricConfig.ACTIVE_CALORIES))
                        })
                    }
                    Box(Modifier.weight(1f)) {
                        MiniMetricCard(stringResource(R.string.label_basal), basalCaloriesBurnedToday.toString(), stringResource(R.string.unit_cal), onClick = {
                            backStack.add(Route.BarChartDetails(HealthMetricConfig.BASAL_METABOLIC_RATE))
                        })
                    }
                }
            }

            // High priority Activity Metrics
            item {
                Steps(backStack, stepsToday)
            }

            if (wheelchairPushesToday > 0) {
                item {
                    WheelchairPushes(backStack, wheelchairPushesToday)
                }
            }

            item {
                Mindfulness(mindfulnessToday)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { ElevationGained(backStack, elevationGainedToday) }
                    Box(Modifier.weight(1f)) { FloorsClimbed(backStack, floorsClimbedToday) }
                }
            }

            item { Distance(backStack,distanceToday) }
            item { HeartRate(backStack, heartRateMaxToday, heartRateMinToday) }
            item { 
                SleepCard(backStack, sleepMinutes)
            }


            // 1. Vitals & Clinical Metrics
            item {
                Text(stringResource(R.string.section_vitals_clinical), style = MaterialTheme.typography.labelLarge)
            }
            item {
                VitalsDashboard(backStack, br, spo2, rhr, hrv, skinTemp, vo2Max, bloodGlucose, bloodPressure)
            }

            // 3. Body Composition
            item {
                Text(stringResource(R.string.section_body_composition), style = MaterialTheme.typography.labelLarge)
            }
            item {
                BodyCompositionDashboard(backStack, height, weight, bodyFat, leanBodyMass, boneMass, bodyWaterMass)
            }

//            item {
//                Text(stringResource(R.string.section_medical_records), style = MaterialTheme.typography.labelLarge)
//            }
//            item {
//                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
//                    Box(Modifier.weight(1f)) {
//                        MiniMetricCard(stringResource(R.string.label_immunizations), "", "", onClick = {
//                            backStack.add(Route.Immunizations)
//                        })
//                    }
//                    Box(Modifier.weight(1f)) {
//                        MiniMetricCard(stringResource(R.string.label_lab_results), "", "", onClick = {
//                            backStack.add(Route.LabResults)
//                        })
//                    }
//                }
//            }
        }
    }
}

@Composable
fun NutritionSummaryCard(res: AggregationResult?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(stringResource(R.string.label_total_intake), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.unit_kcal_format, res?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories?.round(0)?.toString() ?: "--"), style = MaterialTheme.typography.headlineSmall)
                }
                Icon(painterResource(R.drawable.baseline_local_fire_department_24), null, tint = Color(255, 165, 0))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NutritionMacro(stringResource(R.string.label_protein), res?.get(NutritionRecord.PROTEIN_TOTAL)?.inGrams, "g")
                NutritionMacro(stringResource(R.string.label_carbs), res?.get(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)?.inGrams, "g")
                NutritionMacro(stringResource(R.string.label_fat), res?.get(NutritionRecord.TOTAL_FAT_TOTAL)?.inGrams, "g")
            }
        }
    }
}

@Composable
fun NutritionMacro(label: String, value: Double?, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.value_unit_format, value?.round(1)?.toString() ?: "--", unit), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun VitalsDashboard(backStack: NavBackStack<Route>, br: Double?, spo2: Double?, rhr: Long?, hrv: Double?, temp: Double?, vo2: Double?, bg: Double?, bp: Pair<Double, Double>?) {
    val items = 8
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricSegmentCard(0, items, stringResource(R.string.label_blood_pressure), bp?.let { stringResource(R.string.blood_pressure_format, it.first.toInt(), it.second.toInt()) }, stringResource(R.string.label_blood_pressure_unit), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.BLOOD_PRESSURE))
        })
        MetricSegmentCard(1, items, stringResource(R.string.label_blood_glucose), bg, stringResource(R.string.unit_mg_dl), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.GLUCOSE))
        })
        MetricSegmentCard(2, items, stringResource(R.string.label_oxygen_saturation), spo2, stringResource(R.string.unit_percent), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.OXYGEN_SATURATION))
        })
        MetricSegmentCard(3, items, stringResource(R.string.label_breathing_rate), br, stringResource(R.string.unit_brpm), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.BREATHING_RATE))
        })
        MetricSegmentCard(4, items, stringResource(R.string.label_resting_heart_rate), rhr?.toDouble(), stringResource(R.string.unit_bpm), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.RESTING_HEART_RATE))
        })
        MetricSegmentCard(5, items, stringResource(R.string.label_hrv_rmssd), hrv, stringResource(R.string.unit_ms), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.HRV))
        })
        MetricSegmentCard(6, items, stringResource(R.string.label_vo2_max), vo2, stringResource(R.string.unit_vo2_max), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.VO2_MAX))
        })
        MetricSegmentCard(7, items, stringResource(R.string.label_skin_temp_var), temp, stringResource(R.string.unit_celsius), showSign = true, onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.SKIN_TEMP))
        })
    }
}

@Composable
fun BodyCompositionDashboard(backStack: NavBackStack<Route>, h: Double?, w: Double?, bf: Double?, lbm: Double?, bm: Double?, bw: Double?) {
    val items = 6
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricSegmentCard(0, items, stringResource(R.string.label_height), h?.let { it * 100 }, stringResource(R.string.unit_cm), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.HEIGHT))
        })
        MetricSegmentCard(1, items, stringResource(R.string.label_weight), w, stringResource(R.string.unit_kg), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.WEIGHT))
        })
        MetricSegmentCard(2, items, stringResource(R.string.label_body_fat), bf, stringResource(R.string.unit_percent), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.BODY_FAT))
        })
        MetricSegmentCard(3, items, stringResource(R.string.label_lean_body_mass), lbm, stringResource(R.string.unit_kg), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.LEAN_BODY_MASS))
        })
        MetricSegmentCard(4, items, stringResource(R.string.label_bone_mass), bm, stringResource(R.string.unit_kg), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.BONE_MASS))
        })
        MetricSegmentCard(5, items, stringResource(R.string.label_body_water_mass), bw, stringResource(R.string.unit_kg), onClick = {
            backStack.add(Route.BarChartDetails(HealthMetricConfig.BODY_WATER_MASS))
        })
    }
}

@Composable
fun MetricSegmentCard(index: Int, total: Int, label: String, value: Any?, unit: String, showSign: Boolean = false, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Card(modifier, verticalSegmentedCardShape(index, total), CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                val displayValue = when (value) {
                    null -> "--"
                    is Double -> if (showSign && value >= 0) stringResource(R.string.plus_value_format, value.round(1).toString()) else value.round(1).toString()
                    else -> value.toString()
                }
                Text(text = displayValue, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(4.dp))
                Text(text = unit, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun MiniMetricCard(label: String, value: String, unit: String, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun verticalSegmentedCardShape(index: Int, total: Int): Shape {
    return when (index) {
        0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
        total - 1 -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        else -> RoundedCornerShape(4.dp)
    }
}

@Composable
fun Nutrition(backStack: NavBackStack<Route>, kcal: Long) {
    GenericCard(stringResource(R.string.label_nutrition), null, stringResource(R.string.unit_kcal), kcal.toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.NutritionDetails)
    }) {
        ProgressBarGraphic(R.drawable.baseline_local_fire_department_24, kcal.toFloat(), 2000f, Color(0xFF4CAF50))
    }
}

@Composable
fun Hydration(backStack: NavBackStack<Route>, ml: Double) {
    GenericCard(stringResource(R.string.label_hydration), null, stringResource(R.string.unit_ml), ml.toInt().toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.HYDRATION))
    }) {
        ProgressBarGraphic(R.drawable.baseline_local_fire_department_24, ml.toFloat(), 3000f, Color(0xFF2196F3))
    }
}

@Composable
fun FloorsClimbed(backStack: NavBackStack<Route>, floors: Double) {
    GenericCard(stringResource(R.string.label_floors), null, stringResource(R.string.unit_fl), floors.round(1).toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.FLOORS))
    }) {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, floors.toFloat(), 10f, Color(0xFFFFA726))
    }
}

@Composable
fun ElevationGained(backStack: NavBackStack<Route>, meters: Double) {
    GenericCard(stringResource(R.string.label_elevation), null, stringResource(R.string.unit_m), meters.round(1).toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.ELEVATION))
    }) {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, meters.toFloat(), 100f, Color(0xFF8D6E63))
    }
}

@Composable
fun Distance(backStack: NavBackStack<Route>, km: Double) {
    GenericCard(stringResource(R.string.label_distance), null, stringResource(R.string.unit_km), km.round(2).toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.DISTANCE))
    }) {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, km.toFloat(), 5.0f, Color(0xFFFFEB3B))
    }
}

@Composable
fun HeartRate(backStack: NavBackStack<Route>, max: Long, min: Long) {
    GenericCard(stringResource(R.string.label_heart_rate), null, stringResource(R.string.unit_bpm), if(max > 0) stringResource(R.string.range_format, min, max) else "--", stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.HEART_RATE))
    }) {
        ProgressBarGraphic(R.drawable.baseline_favorite_24, max.toFloat(), 200f, Color(0xFFF44336))
    }
}

@Composable
fun SleepCard(backStack: NavBackStack<Route>, min: Long?) {
    val context = LocalContext.current
    val dateString = if (min != null) stringResource(R.string.label_last_night) else "No Data"
    val sleepValue = if (min != null) hoursMinutesString(context, min) else "--"
    
    GenericCard(stringResource(R.string.label_sleep), null, "", sleepValue, dateString, onClick = {
        backStack.add(Route.SleepDetails)
    }) {
        ProgressBarGraphic(R.drawable.baseline_bedtime_24, min?.toFloat() ?: 0f, 480f, Color(0xFF9C27B0))
    }
}

@Composable
fun Steps(backStack: NavBackStack<Route>, count: Long) {
    GenericCard(stringResource(R.string.label_steps), null, stringResource(R.string.unit_steps), count.toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.STEPS))
    }) {
        ProgressBarGraphic(R.drawable.outline_directions_walk_24, count.toFloat(), 10000f, Color(0xFF03A9F4))
    }
}

@Composable
fun WheelchairPushes(backStack: NavBackStack<Route>, count: Long) {
    GenericCard(stringResource(R.string.label_wheelchair_pushes), null, stringResource(R.string.unit_pushes), count.toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.WHEELCHAIR_PUSHES))
    }) {
        ProgressBarGraphic(R.drawable.outline_directions_walk_24, count.toFloat(), 5000f, Color(0xFF00BCD4))
    }
}

@Composable
fun Mindfulness(min: Long) {
    GenericCard(stringResource(R.string.label_mindfulness), null, stringResource(R.string.unit_min), min.toString(), stringResource(R.string.label_today)) {
        ProgressBarGraphic(R.drawable.baseline_bedtime_24, min.toFloat(), 20f, Color(0xFF81C784))
    }
}

@Composable
fun EnergyBurned(backStack: NavBackStack<Route>, kcal: Long) {
    GenericCard(stringResource(R.string.label_energy), null, stringResource(R.string.unit_cal), kcal.toString(), stringResource(R.string.label_today), onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.ENERGY))
    }) {
        ProgressBarGraphic(R.drawable.baseline_local_fire_department_24, kcal.toFloat(), 2500f, Color(0xFF00E676))
    }
}

@Composable
fun GenericCard(name: String, titleIcon: ImageVector?, units: String, number: String, dateString: String, shape: Shape = CardDefaults.shape, onClick: (() -> Unit)? = null, graphic: @Composable (() -> Unit) = {}) {
    val modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Card(modifier.fillMaxWidth(), shape = shape) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                    if (titleIcon != null) Icon(titleIcon, null, Modifier.padding(start = 4.dp).size(16.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = number, style = MaterialTheme.typography.headlineLarge, fontSize = 32.sp)
                    Text(text = units, Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Text(text = dateString, style = MaterialTheme.typography.bodySmall)
            }
            graphic()
        }
    }
}

@Composable
fun ProgressBarGraphic(icon: Int, number: Float, total: Float, color: Color) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(54.dp)) {
        CircularProgressIndicator(progress = { if (total > 0) (number / total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxSize(), color = color, strokeWidth = 4.dp, trackColor = color.copy(alpha = 0.2f))
        Icon(painterResource(id = icon), null, tint = color, modifier = Modifier.size(20.dp))
    }
}

fun hoursMinutesString(context: android.content.Context, minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) context.getString(R.string.hours_minutes_format, h, m) else context.getString(R.string.minutes_format, m)
}
