package com.vayunmathur.health.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.*
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.health.HealthAPI
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.database.RecordType
import com.vayunmathur.health.fhir.Patient
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.toKotlinDuration

val JSON = kotlinx.serialization.json.Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

@OptIn(ExperimentalPersonalHealthRecordApi::class)
@Composable
fun MainPage(backStack: NavBackStack<Route>) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    // Health Metrics States (Point-in-time)
    var br by remember { mutableStateOf<Double?>(null) }
    var spo2 by remember { mutableStateOf<Double?>(null) }
    var hrv by remember { mutableStateOf<Double?>(null) }
    var rhr by remember { mutableStateOf<Long?>(null) }
    var skinTemp by remember { mutableStateOf<Double?>(null) }
    var vo2Max by remember { mutableStateOf<Double?>(null) }
    var bloodGlucose by remember { mutableStateOf<Double?>(null) }
    var bloodPressure by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // Body Measurement States
    var height by remember { mutableStateOf<Double?>(null) }
    var weight by remember { mutableStateOf<Double?>(null) }
    var bodyFat by remember { mutableStateOf<Double?>(null) }
    var boneMass by remember { mutableStateOf<Double?>(null) }
    var leanBodyMass by remember { mutableStateOf<Double?>(null) }
    var bodyWaterMass by remember { mutableStateOf<Double?>(null) }
    
    val dayStart = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.atStartOfDayIn(TimeZone.currentSystemDefault())
    val dayEnd = dayStart.plus(24.hours)

    val totalCaloriesBurnedToday by HealthAPI.sumInRange(RecordType.CaloriesTotal, dayStart, dayEnd).map { it.toLong() }.collectAsState(0L)
    val activeCaloriesBurnedToday by HealthAPI.sumInRange(RecordType.CaloriesActive, dayStart, dayEnd).map { it.toLong() }.collectAsState(0L)
    val basalCaloriesBurnedToday by HealthAPI.sumInRange(RecordType.CaloriesBasal, dayStart, dayEnd).map { it.toLong() }.collectAsState(0L)
    val stepsToday by HealthAPI.sumInRange(RecordType.Steps, dayStart, dayEnd).map { it.toLong() }.collectAsState(0L)
    val wheelchairPushesToday by HealthAPI.sumInRange(RecordType.Wheelchair, dayStart, dayEnd).map { it.toLong() }.collectAsState(0L)
    val mindfulnessToday by HealthAPI.sumInRange(RecordType.Mindfulness, dayStart, dayEnd).map { it.toLong() }.collectAsState(0L)
    val distanceToday by HealthAPI.sumInRange(RecordType.Distance, dayStart, dayEnd).collectAsState(0.0)
    val floorsClimbedToday by HealthAPI.sumInRange(RecordType.Floors, dayStart, dayEnd).collectAsState(0.0)
    val elevationGainedToday by HealthAPI.sumInRange(RecordType.Elevation, dayStart, dayEnd).collectAsState(0.0)
    val hydrationToday by HealthAPI.sumInRange(RecordType.Hydration, dayStart, dayEnd).collectAsState(0.0) // Liters
//    val caloriesConsumedToday by HealthAPI.sumInRange(RecordType.NutritionEnergy, dayStart, dayEnd).collectAsState(0.0)
//    val proteinToday by HealthAPI.sumInRange(RecordType.Protein, dayStart, dayEnd).collectAsState(0.0) // Grams
//    val carbsToday by HealthAPI.sumInRange(RecordType.Carbohydrates, dayStart, dayEnd).collectAsState(0.0)
//    val fatToday by HealthAPI.sumInRange(RecordType.Fat, dayStart, dayEnd).collectAsState(0.0)

    val heartRateMaxToday by HealthAPI.maxInRange(RecordType.HeartRate, dayStart, dayEnd).map{it.toLong()}.collectAsState(0L)
    val heartRateMinToday by HealthAPI.minInRange(RecordType.HeartRate, dayStart, dayEnd).map{it.toLong()}.collectAsState(0L)

//    val sleepDurationToday by HealthAPI.sumInRange(RecordType.Sleep, dayStart, dayEnd).map { it.toLong() }.collectAsState(0L)

    LaunchedEffect(now.date) {
        // 2. Fetch Latest Records (Point-in-time metrics)
        withContext(Dispatchers.IO) {
            spo2 = HealthAPI.lastRecord(RecordType.OxygenSaturation)?.value
            br = HealthAPI.lastRecord(RecordType.RespiratoryRate)?.value
            hrv = HealthAPI.lastRecord(RecordType.HeartRateVariabilityRmssd)?.value
            rhr = HealthAPI.lastRecord(RecordType.RestingHeartRate)?.value?.toLong()
            skinTemp = HealthAPI.lastRecord(RecordType.SkinTemperature)?.value
            vo2Max = HealthAPI.lastRecord(RecordType.Vo2Max)?.value
            bloodGlucose = HealthAPI.lastRecord(RecordType.BloodGlucose)?.value
            bloodPressure = HealthAPI.lastRecord(RecordType.BloodPressure)?.let {
                it.value to it.secondaryValue
            }
            height = HealthAPI.lastRecord(RecordType.Height)?.value
            weight = HealthAPI.lastRecord(RecordType.Weight)?.value
            bodyFat = HealthAPI.lastRecord(RecordType.BodyFat)?.value
            boneMass = HealthAPI.lastRecord(RecordType.BoneMass)?.value
            leanBodyMass = HealthAPI.lastRecord(RecordType.LeanBodyMass)?.value
            bodyWaterMass = HealthAPI.lastRecord(RecordType.BodyWaterMass)?.value
        }
    }

    Scaffold { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text("Medical Records", style = MaterialTheme.typography.labelLarge)
            Card(Modifier.invisibleClickable{
                backStack.add(Route.MedicalRecords)
            }) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Medical Records")
                }
            }

            // 4. Activity & Energy
            Text("Activity", style = MaterialTheme.typography.labelLarge)
            EnergyBurned(backStack, totalCaloriesBurnedToday)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    MiniMetricCard("Active", activeCaloriesBurnedToday.toString(), "cal", onClick = {
                        backStack.add(Route.BarChartDetails(HealthMetricConfig.ACTIVE_CALORIES))
                    })
                }
                Box(Modifier.weight(1f)) {
                    MiniMetricCard("Basal", basalCaloriesBurnedToday.toString(), "cal", onClick = {
                        backStack.add(Route.BarChartDetails(HealthMetricConfig.BASAL_METABOLIC_RATE))
                    })
                }
            }

            // High priority Activity Metrics
            Steps(backStack, stepsToday)

            if (wheelchairPushesToday > 0) {
                WheelchairPushes(backStack, wheelchairPushesToday)
            }

            Mindfulness(mindfulnessToday)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ElevationGained(backStack, elevationGainedToday) }
                Box(Modifier.weight(1f)) { FloorsClimbed(backStack, floorsClimbedToday) }
            }

            Distance(backStack,distanceToday)
            HeartRate(heartRateMaxToday, heartRateMinToday)
            // TODO: add this back
            //Sleep(aggregates?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL)?.toKotlinDuration()?.inWholeMinutes ?: 0)


            // 1. Vitals & Clinical Metrics
            Text("Vitals & Clinical", style = MaterialTheme.typography.labelLarge)
            VitalsDashboard(br, spo2, rhr, hrv, skinTemp, vo2Max, bloodGlucose, bloodPressure)

            // 2. Nutrition Summary
            Text("Nutrition (Today)", style = MaterialTheme.typography.labelLarge)
            // TODO: add this back
            //NutritionSummaryCard(aggregates)
            Hydration(hydrationToday)

            // 3. Body Composition
            Text("Body Composition", style = MaterialTheme.typography.labelLarge)
            BodyCompositionDashboard(height, weight, bodyFat, leanBodyMass, boneMass, bodyWaterMass)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun NutritionSummaryCard(res: AggregationResult?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Total Intake", style = MaterialTheme.typography.labelMedium)
                    Text("${res?.get(NutritionRecord.ENERGY_TOTAL)?.inKilocalories?.round(0) ?: "--"} kcal", style = MaterialTheme.typography.headlineSmall)
                }
                Icon(painterResource(R.drawable.baseline_local_fire_department_24), null, tint = Color(255, 165, 0))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NutritionMacro("Protein", res?.get(NutritionRecord.PROTEIN_TOTAL)?.inGrams, "g")
                NutritionMacro("Carbs", res?.get(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)?.inGrams, "g")
                NutritionMacro("Fat", res?.get(NutritionRecord.TOTAL_FAT_TOTAL)?.inGrams, "g")
            }
        }
    }
}

@Composable
fun NutritionMacro(label: String, value: Double?, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text("${value?.round(1) ?: "--"}$unit", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun VitalsDashboard(br: Double?, spo2: Double?, rhr: Long?, hrv: Double?, temp: Double?, vo2: Double?, bg: Double?, bp: Pair<Double, Double>?) {
    val items = 8
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricSegmentCard(0, items, "Blood Pressure", bp?.let { "${it.first.toInt()}/${it.second.toInt()}" }, "mmHg")
        MetricSegmentCard(1, items, "Blood Glucose", bg, "mg/dL")
        MetricSegmentCard(2, items, "Oxygen Saturation", spo2, "%")
        MetricSegmentCard(3, items, "Breathing Rate", br, "brpm")
        MetricSegmentCard(4, items, "Resting Heart Rate", rhr?.toDouble(), "bpm")
        MetricSegmentCard(5, items, "HRV (RMSSD)", hrv, "ms")
        MetricSegmentCard(6, items, "VO2 Max", vo2, "mL/kg/min")
        MetricSegmentCard(7, items, "Skin Temp Var", temp, "°C", showSign = true)
    }
}

@Composable
fun BodyCompositionDashboard(h: Double?, w: Double?, bf: Double?, lbm: Double?, bm: Double?, bw: Double?) {
    val items = 6
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MetricSegmentCard(0, items, "Height", h?.let { it * 100 }, "cm")
        MetricSegmentCard(1, items, "Weight", w, "kg")
        MetricSegmentCard(2, items, "Body Fat", bf, "%")
        MetricSegmentCard(3, items, "Lean Body Mass", lbm, "kg")
        MetricSegmentCard(4, items, "Bone Mass", bm, "kg")
        MetricSegmentCard(5, items, "Body Water Mass", bw, "kg")
    }
}

@Composable
fun MetricSegmentCard(index: Int, total: Int, label: String, value: Any?, unit: String, showSign: Boolean = false) {
    Card(
        shape = verticalSegmentedCardShape(index, total),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                    is Double -> if (showSign) (if (value >= 0) "+" else "") + value.round(1) else value.round(1).toString()
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
fun Hydration(ml: Double) {
    GenericCard("Hydration", null, "mL", ml.toInt().toString(), "Today") {
        ProgressBarGraphic(R.drawable.baseline_local_fire_department_24, ml.toFloat(), 3000f, Color(0xFF2196F3))
    }
}

@Composable
fun FloorsClimbed(backStack: NavBackStack<Route>, floors: Double) {
    GenericCard("Floors", null, "fl", floors.round(1).toString(), "Today", onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.FLOORS))
    }) {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, floors.toFloat(), 10f, Color(0xFFFFA726))
    }
}

@Composable
fun ElevationGained(backStack: NavBackStack<Route>, meters: Double) {
    GenericCard("Elevation", null, "m", meters.round(1).toString(), "Today", onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.ELEVATION))
    }) {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, meters.toFloat(), 100f, Color(0xFF8D6E63))
    }
}

@Composable
fun Distance(backStack: NavBackStack<Route>, km: Double) {
    GenericCard("Distance", null, "km", "${km.round(2)}", "Today", onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.DISTANCE))
    }) {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, km.toFloat(), 5.0f, Color(0xFFFFEB3B))
    }
}

@Composable
fun HeartRate(max: Long, min: Long) {
    GenericCard("Heart rate", null, "bpm", if(max > 0) "$min-$max" else "--", "Today") {
        ProgressBarGraphic(R.drawable.baseline_favorite_24, max.toFloat(), 200f, Color(0xFFF44336))
    }
}

@Composable
fun Sleep(min: Long) {
    GenericCard("Sleep", null, "hr", hoursMinutesString(min), "Last night") {
        ProgressBarGraphic(R.drawable.baseline_bedtime_24, min.toFloat(), 480f, Color(0xFF9C27B0))
    }
}

@Composable
fun Steps(backStack: NavBackStack<Route>, count: Long) {
    GenericCard("Steps", null, "steps", count.toString(), "Today", onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.STEPS))
    }) {
        ProgressBarGraphic(R.drawable.outline_directions_walk_24, count.toFloat(), 10000f, Color(0xFF03A9F4))
    }
}

@Composable
fun WheelchairPushes(backStack: NavBackStack<Route>, count: Long) {
    GenericCard("Wheelchair Pushes", null, "pushes", count.toString(), "Today", onClick = {
        backStack.add(Route.BarChartDetails(HealthMetricConfig.WHEELCHAIR_PUSHES))
    }) {
        ProgressBarGraphic(R.drawable.outline_directions_walk_24, count.toFloat(), 5000f, Color(0xFF00BCD4))
    }
}

@Composable
fun Mindfulness(min: Long) {
    GenericCard("Mindfulness", null, "min", min.toString(), "Today") {
        ProgressBarGraphic(R.drawable.baseline_bedtime_24, min.toFloat(), 20f, Color(0xFF81C784))
    }
}

@Composable
fun EnergyBurned(backStack: NavBackStack<Route>, kcal: Long) {
    GenericCard("Energy", null, "cal", kcal.toString(), "Today", onClick = {
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

fun hoursMinutesString(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}