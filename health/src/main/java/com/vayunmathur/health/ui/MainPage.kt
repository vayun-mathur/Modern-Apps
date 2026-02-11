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
import com.vayunmathur.health.fhir.Patient
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.toKotlinDuration

val JSON = kotlinx.serialization.json.Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

@OptIn(ExperimentalPersonalHealthRecordApi::class)
@Composable
fun MainPage(backStack: NavBackStack<Route>) {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    var aggregates: AggregationResult? by remember { mutableStateOf(null) }

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

    LaunchedEffect(now.date) {
        // 1. Fetch Aggregates (Cumulative time-series data)
        aggregates = withContext(Dispatchers.IO) {
            HealthAPI.aggregates(
                HealthAPI.timeRangeToday(),
                TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL,
                StepsRecord.COUNT_TOTAL,
                WheelchairPushesRecord.COUNT_TOTAL, // New
                // MindfulnessSessionRecord.MINDFULNESS_DURATION_TOTAL, TODO: add back when supported
                DistanceRecord.DISTANCE_TOTAL,
                FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL,
                ElevationGainedRecord.ELEVATION_GAINED_TOTAL,
                HydrationRecord.VOLUME_TOTAL,
                NutritionRecord.ENERGY_TOTAL,
                NutritionRecord.PROTEIN_TOTAL,
                NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL,
                NutritionRecord.TOTAL_FAT_TOTAL,
                HeartRateRecord.BPM_MAX,
                HeartRateRecord.BPM_MIN,
                SleepSessionRecord.SLEEP_DURATION_TOTAL,
            )
        }

        // 2. Fetch Latest Records (Point-in-time metrics)
        withContext(Dispatchers.IO) {
            spo2 = HealthAPI.lastRecord<OxygenSaturationRecord>()?.percentage?.value
            br = HealthAPI.lastRecord<RespiratoryRateRecord>()?.rate
            hrv = HealthAPI.lastRecord<HeartRateVariabilityRmssdRecord>()?.heartRateVariabilityMillis
            rhr = HealthAPI.lastRecord<RestingHeartRateRecord>()?.beatsPerMinute
            skinTemp = HealthAPI.lastRecord<SkinTemperatureRecord>()?.deltas?.lastOrNull()?.delta?.inCelsius
            vo2Max = HealthAPI.lastRecord<Vo2MaxRecord>()?.vo2MillilitersPerMinuteKilogram
            bloodGlucose = HealthAPI.lastRecord<BloodGlucoseRecord>()?.level?.inMilligramsPerDeciliter
            bloodPressure = HealthAPI.lastRecord<BloodPressureRecord>()?.let {
                it.systolic.inMillimetersOfMercury to it.diastolic.inMillimetersOfMercury
            }
            height = HealthAPI.lastRecord<HeightRecord>()?.height?.inMeters
            weight = HealthAPI.lastRecord<WeightRecord>()?.weight?.inKilograms
            bodyFat = HealthAPI.lastRecord<BodyFatRecord>()?.percentage?.value
            boneMass = HealthAPI.lastRecord<BoneMassRecord>()?.mass?.inKilograms
            leanBodyMass = HealthAPI.lastRecord<LeanBodyMassRecord>()?.mass?.inKilograms
            bodyWaterMass = HealthAPI.lastRecord<BodyWaterMassRecord>()?.mass?.inKilograms
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

            // 1. Vitals & Clinical Metrics
            Text("Vitals & Clinical", style = MaterialTheme.typography.labelLarge)
            VitalsDashboard(br, spo2, rhr, hrv, skinTemp, vo2Max, bloodGlucose, bloodPressure)

            // 2. Nutrition Summary
            Text("Nutrition (Today)", style = MaterialTheme.typography.labelLarge)
            NutritionSummaryCard(aggregates)

            // 3. Body Composition
            Text("Body Composition", style = MaterialTheme.typography.labelLarge)
            BodyCompositionDashboard(height, weight, bodyFat, leanBodyMass, boneMass, bodyWaterMass)

            // 4. Activity & Energy
            Text("Activity", style = MaterialTheme.typography.labelLarge)
            EnergyBurned(backStack, aggregates?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    MiniMetricCard("Active", (aggregates?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories ?: 0.0).round(0).toInt().toString(), "cal")
                }
                Box(Modifier.weight(1f)) {
                    MiniMetricCard("Basal", (aggregates?.get(BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL)?.inKilocalories ?: 0.0).round(0).toInt().toString(), "cal")
                }
            }

            // High priority Activity Metrics
            Steps(aggregates?.get(StepsRecord.COUNT_TOTAL) ?: 0)

            val pushes = aggregates?.get(WheelchairPushesRecord.COUNT_TOTAL) ?: 0
            if (pushes > 0) {
                WheelchairPushes(pushes)
            }

            // Mindfulness(aggregates?.get(MindfulnessSessionRecord.MINDFULNESS_DURATION_TOTAL)?.toKotlinDuration()?.inWholeMinutes ?: 0)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ElevationGained(aggregates?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters ?: 0.0) }
                Box(Modifier.weight(1f)) { FloorsClimbed(aggregates?.get(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) ?: 0.0) }
            }

            Distance(aggregates?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers ?: 0.0)
            HeartRate(aggregates?.get(HeartRateRecord.BPM_MAX) ?: 0, aggregates?.get(HeartRateRecord.BPM_MIN) ?: 0)
            Sleep(aggregates?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL)?.toKotlinDuration()?.inWholeMinutes ?: 0)
            Hydration(aggregates?.get(HydrationRecord.VOLUME_TOTAL)?.inMilliliters ?: 0.0)

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
fun MiniMetricCard(label: String, value: String, unit: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
fun FloorsClimbed(floors: Double) {
    GenericCard("Floors", null, "fl", floors.round(1).toString(), "Today") {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, floors.toFloat(), 10f, Color(0xFFFFA726))
    }
}

@Composable
fun ElevationGained(meters: Double) {
    GenericCard("Elevation", null, "m", meters.round(1).toString(), "Today") {
        ProgressBarGraphic(R.drawable.baseline_location_pin_24, meters.toFloat(), 100f, Color(0xFF8D6E63))
    }
}

@Composable
fun Distance(km: Double) {
    GenericCard("Distance", null, "km", "${km.round(2)}", "Today") {
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
fun Steps(count: Long) {
    GenericCard("Steps", null, "steps", count.toString(), "Today") {
        ProgressBarGraphic(R.drawable.outline_directions_walk_24, count.toFloat(), 10000f, Color(0xFF03A9F4))
    }
}

@Composable
fun WheelchairPushes(count: Long) {
    GenericCard("Wheelchair Pushes", null, "pushes", count.toString(), "Today") {
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
fun EnergyBurned(backStack: NavBackStack<Route>, kcal: Double) {
    GenericCard("Energy", null, "cal", kcal.round(0).toInt().toString(), "Today", onClick = {
        backStack.add(Route.BarChartDetails)
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