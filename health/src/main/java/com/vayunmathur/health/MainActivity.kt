package com.vayunmathur.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.MaActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.ReferenceCatalog
import com.vayunmathur.health.platform.HealthConnectAvailability
import com.vayunmathur.health.platform.HealthConnectSetup
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.MedicalViewModelFactory
import com.vayunmathur.health.platform.PersonalHealthRecords
import com.vayunmathur.health.ui.AddAllergyPage
import com.vayunmathur.health.ui.AddConditionPage
import com.vayunmathur.health.ui.AddLabResultPage
import com.vayunmathur.health.ui.AddMedicationPage
import com.vayunmathur.health.ui.AddVaccinationPage
import com.vayunmathur.health.ui.BarChartDetails
import com.vayunmathur.health.ui.BodyPage
import com.vayunmathur.health.ui.CatalogPickerPage
import com.vayunmathur.health.ui.ExerciseDetailsPage
import com.vayunmathur.health.ui.HealthConnectRequiredPage
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.MedicalPage

import com.vayunmathur.health.ui.NutritionDetailsPage
import com.vayunmathur.health.ui.NutritionPage
import com.vayunmathur.health.ui.RecipeEditorPage
import com.vayunmathur.health.ui.RecipeManagementPage
import com.vayunmathur.health.ui.TodayPage
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthSyncWorker
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.HealthViewModelFactory
import com.vayunmathur.library.ui.AppPermissionsGate
import com.vayunmathur.library.ui.AppPermissionsSpec
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconMedicalServices
import com.vayunmathur.library.ui.PermissionRequirement
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

val CLASSES = setOf(
    // Activity & Energy
    StepsRecord::class,
    WheelchairPushesRecord::class,
    DistanceRecord::class,
    TotalCaloriesBurnedRecord::class,
    ActiveCaloriesBurnedRecord::class,
    BasalMetabolicRateRecord::class,
    FloorsClimbedRecord::class,
    ElevationGainedRecord::class,

    // Vitals & Clinical
    HeartRateRecord::class,
    RestingHeartRateRecord::class,
    HeartRateVariabilityRmssdRecord::class,
    RespiratoryRateRecord::class,
    OxygenSaturationRecord::class,
    BloodPressureRecord::class,
    BloodGlucoseRecord::class,
    Vo2MaxRecord::class,
    SkinTemperatureRecord::class,

    // Body Composition
    WeightRecord::class,
    HeightRecord::class,
    BodyFatRecord::class,
    LeanBodyMassRecord::class,
    BoneMassRecord::class,
    BodyWaterMassRecord::class,

    // Exercise
    ExerciseSessionRecord::class,

    // Lifestyle & Nutrition
    MindfulnessSessionRecord::class,
    HydrationRecord::class,
    NutritionRecord::class,
    SleepSessionRecord::class,
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


class MainActivity : MaActivity() {
    private val healthViewModel: HealthViewModel by viewModels {
        HealthViewModelFactory(application, HealthRepository.get(this))
    }
    private val medicalViewModel: MedicalViewModel by viewModels {
        MedicalViewModelFactory(application, HealthRepository.get(this))
    }
    private lateinit var healthConnectClient: HealthConnectClient

    /**
     * Creates the Health Connect client when the provider is usable, wiring it into HealthAPI
     * and PersonalHealthRecords.
     *
     * Idempotent: called from `onCreateBeforeContent` and again on every resume, because the
     * user can install the provider while this activity is alive. When the provider is missing
     * or too old this leaves HealthAPI in local-only mode and PersonalHealthRecords
     * uninitialised (its `isAvailable` reports false with no client).
     */
    private fun ensureHealthConnect() {
        if (HealthConnectSetup.current(this) != HealthConnectAvailability.AVAILABLE) {
            HealthAPI.initLocal(this, HealthRepository.get(this))
            return
        }
        if (!::healthConnectClient.isInitialized) {
            healthConnectClient = HealthConnectClient.getOrCreate(this)
        }
        val repository = HealthRepository.get(this)
        HealthAPI.init(healthConnectClient, this, repository)
        PersonalHealthRecords.init(this, healthConnectClient)
    }

    override fun onCreateBeforeContent() {
        // Health Connect is a separate APK on API 33 and below and getOrCreate throws when it is
        // missing or too old, which crashed launch outright. Its setup is gated on the SDK status;
        // Room, the food database and the reference catalog work without it, and the composable
        // below shows the install screen instead of the app until it is usable.
        ensureHealthConnect()
        FoodDatabase.init(this)
        ReferenceCatalog.init(this)
        // Unpacking is half a second of decompression and disk write. Do it now, in the background,
        // rather than the first time the user opens a picker and waits for it.
        ReferenceCatalog.warmUp(lifecycleScope)
    }

    @Composable
    override fun Content() {
        // Re-checked on every resume: returning from the Play Store install clears this screen
        // without a relaunch. The status call is a cached PackageManager lookup, not IPC.
        var availability by remember {
            mutableStateOf(HealthConnectSetup.current(this@MainActivity))
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    // The user may be returning from the Play Store install. Set up the client
                    // first so the gate and the screens below never see an uninitialised one.
                    ensureHealthConnect()
                    availability = HealthConnectSetup.current(this@MainActivity)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (availability != HealthConnectAvailability.AVAILABLE) {
            // Health Connect missing or too old: show the installer screen instead of the app.
            // Composing the permission gate here would launch its own getOrCreate probe and,
            // worse, a Grant button whose contract has no provider to answer it.
            HealthConnectRequiredPage(availability) {
                HealthConnectSetup.openInstaller(this@MainActivity)
            }
            return
        }

        val requestMedicalPermissions = rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
            onResult = { /* Refusal is fine — the medical screens fall back to local data. */ }
        )

        AppPermissionsGate(
            spec = AppPermissionsSpec(
                title = stringResource(R.string.grant_permissions),
                subtitle = stringResource(R.string.grant_permissions_rationale),
                icon = { IconFavorite() },
                requirements = listOf(
                    // Dose reminders post notifications; without this grant they would silently
                    // never appear. No-op pre-Tiramisu, where notifications are not runtime-gated.
                    PermissionRequirement.notifications(),
                    PermissionRequirement.HealthConnect(PERMISSIONS),
                )
            )
        ) {
            LaunchedEffect(Unit) {
                HealthSyncWorker.enqueue(this@MainActivity)
            }
            // FHIR record access is requested separately and is allowed to fail. It only
            // exists on Android 15 with an updated Health Connect module, so folding it
            // into PERMISSIONS above would gate the whole app on something most supported
            // devices cannot grant; the medical screens work from Room without it.
            LaunchedEffect(Unit) {
                if (PersonalHealthRecords.isAvailable() &&
                    ::healthConnectClient.isInitialized &&
                    !healthConnectClient.permissionController.getGrantedPermissions()
                        .containsAll(PersonalHealthRecords.PERMISSIONS)
                ) {
                    requestMedicalPermissions.launch(PersonalHealthRecords.PERMISSIONS)
                }
            }
            Navigation(healthViewModel, medicalViewModel)
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Today: Route

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

    @Serializable
    data object Medical: Route

    /** The vaccination form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditVaccination(val id: String? = null): Route

    /** The medication form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditMedication(val id: String? = null): Route

    /** The allergy form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditAllergy(val id: String? = null): Route

    /** The condition form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditCondition(val id: String? = null): Route

    /** The lab result form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditLabResult(val id: String? = null): Route

    /**
     * The searchable reference-data picker. [ingredient] narrows
     * [com.vayunmathur.health.ui.CatalogKind.MedicationProduct] to one drug and is unused otherwise.
     *
     * Carries no result key: the picker writes straight into the ViewModel's draft, because this is
     * a full-screen destination and the form underneath is not composed while it is open.
     */
    @Serializable
    data class CatalogPicker(
        val kind: com.vayunmathur.health.ui.CatalogKind,
        val ingredient: String? = null,
    ): Route

    /**
     * The shared date picker, hosted here so the medical forms can reach it.
     *
     * [allowClear] adds a Clear button and changes the result type to `DateSelection`; set it for
     * any field that is allowed to have no date.
     */
    @Serializable
    data class MedicalDatePicker(
        val key: String,
        val initialDate: LocalDate,
        val allowClear: Boolean = false,
    ): Route

    /** The shared time picker, for dose reminder times. */
    @Serializable
    data class MedicalTimePicker(val key: String, val initialTime: LocalTime): Route
}

@Composable
fun Navigation(viewModel: HealthViewModel, medicalViewModel: MedicalViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Today)
    MainNavigation(
        backStack = backStack,
        bottomBar = {
            com.vayunmathur.library.util.BottomNavBar(
                backStack = backStack,
                pages = bottomBarItems(),
                currentPage = backStack.last()
            )
        }
    ) {
        mainEntries(backStack, viewModel)
        medicalEntries(backStack, medicalViewModel)
    }
}

/** The four bottom tabs, in order. */
@Composable
private fun bottomBarItems() = listOf(
    com.vayunmathur.library.util.BottomBarItem(
        stringResource(R.string.nav_today),
        Route.Today,
    ) { IconFavorite() },
    com.vayunmathur.library.util.BottomBarItem(
        stringResource(R.string.nav_nutrition),
        Route.NutritionDetails,
    ) { IconFire() },
    com.vayunmathur.library.util.BottomBarItem(
        stringResource(R.string.nav_body),
        Route.Body,
    ) { IconBodySystem() },
    com.vayunmathur.library.util.BottomBarItem(
        stringResource(R.string.nav_medical),
        Route.Medical,
) { IconMedicalServices() },
)

/** Fitness and nutrition destinations. */
private fun EntryProviderScope<Route>.mainEntries(
    backStack: NavBackStack<Route>,
    viewModel: HealthViewModel,
) {
        entry<Route.Today>(metadata = SiblingPage()) {
            TodayPage(backStack, viewModel)
        }
        entry<Route.Body>(metadata = SiblingPage()) {
            BodyPage(backStack, viewModel)
        }
        entry<Route.NutritionDetails>(metadata = SiblingPage()) {
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
        entry<Route.BarChartDetails>(metadata = MorphPage()) {
            BarChartDetails(backStack, viewModel, it.healthMetric)
        }
        entry<Route.SleepDetails>(metadata = MorphPage()) {
            com.vayunmathur.health.ui.SleepDetailsPage(backStack, viewModel)
        }
        entry<Route.ExerciseDetails>(metadata = MorphPage()) {
            ExerciseDetailsPage(backStack, viewModel)
        }
}

/** Medical records destinations. */
private fun EntryProviderScope<Route>.medicalEntries(
    backStack: NavBackStack<Route>,
    medicalViewModel: MedicalViewModel,
) {
        entry<Route.Medical>(metadata = SiblingPage() + ListPage()) {
            MedicalPage(backStack, medicalViewModel)
        }
        entry<Route.EditVaccination>(metadata = ListDetailPage()) {
            AddVaccinationPage(backStack, medicalViewModel)
        }
        entry<Route.EditMedication>(metadata = ListDetailPage()) {
            AddMedicationPage(backStack, medicalViewModel)
        }
        entry<Route.EditAllergy>(metadata = ListDetailPage()) {
            AddAllergyPage(backStack, medicalViewModel)
        }
        entry<Route.EditCondition>(metadata = ListDetailPage()) {
            AddConditionPage(backStack, medicalViewModel)
        }
        entry<Route.EditLabResult>(metadata = ListDetailPage()) {
            AddLabResultPage(backStack, medicalViewModel)
        }
        entry<Route.CatalogPicker>(metadata = ListDetailPage()) {
            CatalogPickerPage(backStack, medicalViewModel, it)
        }
        entry<Route.MedicalDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(backStack, it.key, it.initialDate, allowClear = it.allowClear)
        }
        entry<Route.MedicalTimePicker>(metadata = DialogPage()) {
            TimePickerDialogContent(backStack, it.key, it.initialTime)
        }
}
