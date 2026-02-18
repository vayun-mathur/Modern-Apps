package com.vayunmathur.health

import android.health.connect.HealthPermissions
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.health.database.HealthDatabase
import com.vayunmathur.health.ui.BarChartDetails
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.MainPage
import com.vayunmathur.health.ui.MedicalRecordsPage
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

val CLASSES = setOf(
    // Activity & Energy
    StepsRecord::class, WheelchairPushesRecord::class, DistanceRecord::class, TotalCaloriesBurnedRecord::class,
    ActiveCaloriesBurnedRecord::class, BasalMetabolicRateRecord::class, FloorsClimbedRecord::class, ElevationGainedRecord::class,

    // Vitals & Clinical
    HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, RespiratoryRateRecord::class,
    OxygenSaturationRecord::class, BloodPressureRecord::class, BloodGlucoseRecord::class, Vo2MaxRecord::class, SkinTemperatureRecord::class,

    // Body Composition
    WeightRecord::class, HeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, BoneMassRecord::class, BodyWaterMassRecord::class,

    // Lifestyle & Nutrition
    MindfulnessSessionRecord::class, HydrationRecord::class, // TODO: readd these: NutritionRecord::class, SleepSessionRecord::class
)

val PERMISSIONS = CLASSES.map { HealthPermission.getReadPermission(it) }.toSet() + if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 16) {setOf(
    HealthPermissions.READ_MEDICAL_DATA_ALLERGIES_INTOLERANCES,
    HealthPermissions.READ_MEDICAL_DATA_CONDITIONS,
    HealthPermissions.READ_MEDICAL_DATA_LABORATORY_RESULTS,
    HealthPermissions.READ_MEDICAL_DATA_MEDICATIONS,
    HealthPermissions.READ_MEDICAL_DATA_PERSONAL_DETAILS,
    HealthPermissions.READ_MEDICAL_DATA_PRACTITIONER_DETAILS,
    HealthPermissions.READ_MEDICAL_DATA_PREGNANCY,
    HealthPermissions.READ_MEDICAL_DATA_PROCEDURES,
    HealthPermissions.READ_MEDICAL_DATA_SOCIAL_HISTORY,
    HealthPermissions.READ_MEDICAL_DATA_VACCINES,
    HealthPermissions.READ_MEDICAL_DATA_VISITS,
    HealthPermissions.READ_MEDICAL_DATA_VITAL_SIGNS
) } else {setOf()}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val healthConnectClient = HealthConnectClient.getOrCreate(this)
        val db = buildDatabase<HealthDatabase>()
        HealthAPI.init(healthConnectClient, this, db)
        setContent {
            DynamicTheme {
                var hasPermissions by remember { mutableStateOf(false) }

                val requestPermissions = rememberLauncherForActivityResult(
                    contract = PermissionController.createRequestPermissionResultContract(),
                    onResult = { granted ->
                        hasPermissions = granted.containsAll(PERMISSIONS)
                    }
                )

                LaunchedEffect(Unit) {
                    hasPermissions = healthConnectClient.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
                }

                if (hasPermissions) {
                    LaunchedEffect(Unit) {
                        HealthSyncWorker.enqueue(this@MainActivity)
                    }
                    Navigation(db)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(onClick = { requestPermissions.launch(PERMISSIONS) }) {
                            Text("Grant Permissions")
                        }
                    }
                }
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MainPage: Route

    @Serializable
    data object MedicalRecords: Route

    @Serializable
    data class BarChartDetails(val healthMetric: HealthMetricConfig): Route
}

@Composable
fun Navigation(db: HealthDatabase) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage)
    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(backStack)
        }
        entry<Route.MedicalRecords> {
            MedicalRecordsPage(backStack)
        }
        entry<Route.BarChartDetails> {
            BarChartDetails(backStack, it.healthMetric)
        }
    }
}