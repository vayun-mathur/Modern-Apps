package com.vayunmathur.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.ui.ActivityPage
import com.vayunmathur.health.ui.BarChartDetails
import com.vayunmathur.health.ui.BodyPage
import com.vayunmathur.health.ui.ExerciseDetailsPage
import com.vayunmathur.health.ui.HealthMetricConfig

import com.vayunmathur.health.ui.NutritionDetailsPage
import com.vayunmathur.health.ui.NutritionPage
import com.vayunmathur.health.ui.RecipeEditorPage
import com.vayunmathur.health.ui.RecipeManagementPage
import com.vayunmathur.health.ui.TodayPage
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthSyncWorker
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.room.buildDatabase
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

    // Exercise
    ExerciseSessionRecord::class,

    // Lifestyle & Nutrition
    MindfulnessSessionRecord::class, HydrationRecord::class, NutritionRecord::class, SleepSessionRecord::class
)

val PERMISSIONS = CLASSES.map { HealthPermission.getReadPermission(it) }.toSet() + 
    setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(HeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    )


class MainActivity : ComponentActivity() {
    private val healthViewModel: HealthViewModel by viewModels()

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
                    Navigation(healthViewModel)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(onClick = { requestPermissions.launch(PERMISSIONS) }) {
                            Text(stringResource(R.string.grant_permissions))
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
    data object Today: Route

    @Serializable
    data object Activity: Route

    @Serializable
    data object Body: Route

    @Serializable
    data object NutritionDetails: Route

    @Serializable
    data object NutritionFullBreakdown: Route

    @Serializable
    data object RecipeManagement: Route

    @Serializable
    data class RecipeEditor(val recipeId: String? = null): Route

    @Serializable
    data class BarChartDetails(val healthMetric: HealthMetricConfig): Route

    @Serializable
    data object SleepDetails: Route

    @Serializable
    data object ExerciseDetails: Route
}

@Composable
fun Navigation(viewModel: HealthViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Today)
    MainNavigation(
        backStack = backStack,
        bottomBar = {
            com.vayunmathur.library.util.BottomNavBar(
                backStack = backStack,
                pages = listOf(
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_today),
                        Route.Today,
                    ) { IconFavorite() },
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_activity),
                        Route.Activity,
                    ) { IconDirectionsWalk() },
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_nutrition),
                        Route.NutritionDetails,
                    ) { IconFire() },
                    com.vayunmathur.library.util.BottomBarItem(
                        stringResource(R.string.nav_body),
                        Route.Body,
                    ) { IconBodySystem() },
                ),
                currentPage = backStack.last()
            )
        }
    ) {
        entry<Route.Today> {
            TodayPage(backStack, viewModel)
        }
        entry<Route.Activity> {
            ActivityPage(backStack, viewModel)
        }
        entry<Route.Body> {
            BodyPage(backStack, viewModel)
        }
        entry<Route.NutritionDetails> {
            NutritionPage(backStack, viewModel)
        }
        entry<Route.NutritionFullBreakdown> {
            NutritionDetailsPage(backStack, viewModel)
        }
        entry<Route.RecipeManagement> {
            RecipeManagementPage(backStack, viewModel)
        }
        entry<Route.RecipeEditor> {
            RecipeEditorPage(backStack, viewModel, it.recipeId)
        }
        entry<Route.BarChartDetails> {
            BarChartDetails(backStack, viewModel, it.healthMetric)
        }
        entry<Route.SleepDetails> {
            com.vayunmathur.health.ui.SleepDetailsPage(backStack, viewModel)
        }
        entry<Route.ExerciseDetails> {
            ExerciseDetailsPage(backStack, viewModel)
        }
    }
}
