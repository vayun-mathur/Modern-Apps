package com.vayunmathur.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.health.connect.client.records.*
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.health.ui.MainPage
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

val PERMISSIONS = setOf(
    // Activity & Energy
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(WheelchairPushesRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
    HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
    HealthPermission.getReadPermission(FloorsClimbedRecord::class),
    HealthPermission.getReadPermission(ElevationGainedRecord::class),

    // Vitals & Clinical
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    HealthPermission.getReadPermission(RespiratoryRateRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    HealthPermission.getReadPermission(BloodPressureRecord::class),
    HealthPermission.getReadPermission(BloodGlucoseRecord::class),
    HealthPermission.getReadPermission(Vo2MaxRecord::class),
    HealthPermission.getReadPermission(SkinTemperatureRecord::class),

    // Body Composition
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.getReadPermission(HeightRecord::class),
    HealthPermission.getReadPermission(BodyFatRecord::class),
    HealthPermission.getReadPermission(LeanBodyMassRecord::class),
    HealthPermission.getReadPermission(BoneMassRecord::class),
    HealthPermission.getReadPermission(BodyWaterMassRecord::class),

    // Lifestyle & Nutrition
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(MindfulnessSessionRecord::class),
    HealthPermission.getReadPermission(HydrationRecord::class),
    HealthPermission.getReadPermission(NutritionRecord::class)
)



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val healthConnectClient = HealthConnectClient.getOrCreate(this)
        HealthAPI.init(healthConnectClient, this)
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
                    Navigation()
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
}

@Composable
fun Navigation() {
    val backStack = rememberNavBackStack(Route.MainPage)
    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage()
        }
    }
}