package com.vayunmathur.findfamily

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.migration.Migration
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.MainPage
import com.vayunmathur.findfamily.ui.UserPage
import com.vayunmathur.findfamily.ui.WaypointEditPage
import com.vayunmathur.findfamily.ui.dialogs.AddLinkDialog
import com.vayunmathur.findfamily.ui.dialogs.AddPersonDialog
import com.vayunmathur.findfamily.ui.SettingsPage
import com.vayunmathur.findfamily.util.LocationTrackingService
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import com.vayunmathur.findfamily.util.Platform
import com.vayunmathur.findfamily.util.ensureSync

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<FFDatabase>(listOf(Migration_1_2, Migration_2_3))
        val viewModel = DatabaseViewModel(db, User::class to db.userDao(), Waypoint::class to db.waypointDao(), LocationValue::class to db.locationValueDao(), TemporaryLink::class to db.temporaryLinkDao())
        val platform = Platform(this)
        setContent {
            DynamicTheme {
                val context = LocalContext.current
                val foregroundPermission = Manifest.permission.ACCESS_FINE_LOCATION
                val notificationPermission = Manifest.permission.POST_NOTIFICATIONS

                var hasForeground by remember {
                    mutableStateOf(ContextCompat.checkSelfPermission(context, foregroundPermission) == PackageManager.PERMISSION_GRANTED)
                }
                var hasNotification by remember {
                    mutableStateOf(
                        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasForeground = ContextCompat.checkSelfPermission(
                                context,
                                foregroundPermission
                            ) == PackageManager.PERMISSION_GRANTED
                            hasNotification = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    notificationPermission
                                ) == PackageManager.PERMISSION_GRANTED
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (!hasForeground || !hasNotification) {
                    NoPermissionsScreen(
                        hasForeground = hasForeground,
                        hasNotification = hasNotification,
                        onForegroundGranted = { hasForeground = true },
                        onNotificationGranted = { hasNotification = true }
                    )
                } else {
                    Main(platform, viewModel)
                }
            }
        }
    }

    @Composable
    fun Main(platform: Platform, viewModel: DatabaseViewModel) {
        val context = LocalContext.current
        val isNetworkEnabled = remember {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        val isGeocoderPresent = remember { Geocoder.isPresent() }

        LaunchedEffect(Unit) {
            ensureSync(this@MainActivity)
            context.startForegroundService(Intent(context, LocationTrackingService::class.java))
        }
        Navigation(platform, viewModel, !isNetworkEnabled || !isGeocoderPresent)
    }
}

val Migration_1_2 = Migration(1, 2) {
    it.execSQL("CREATE INDEX IF NOT EXISTS index_LocationValue_timestamp ON LocationValue (timestamp)")
}

val Migration_2_3 = Migration(2, 3) {
    it.execSQL(
        "CREATE INDEX IF NOT EXISTS `index_LocationValue_userid_timestamp` " +
                "ON `LocationValue` (`userid`, `timestamp`)"
    )}

@Composable
fun NoPermissionsScreen(
    hasForeground: Boolean,
    hasNotification: Boolean,
    onForegroundGranted: () -> Unit,
    onNotificationGranted: () -> Unit
) {
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) onForegroundGranted()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) onNotificationGranted()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { foregroundLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                enabled = !hasForeground
            ) {
                Text(if (hasForeground) stringResource(R.string.permission_location_granted) else stringResource(R.string.permission_grant_location))
            }

            Spacer(Modifier.height(16.dp))

            if (hasForeground) {
                Button(
                    onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    enabled = !hasNotification
                ) {
                    Text(if (hasNotification) stringResource(R.string.permission_notification_granted) else stringResource(R.string.permission_grant_notification))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permission_location_explanation),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
fun MissingFeaturesDialog(backStack: NavBackStack<Route>) {
    Surface(
        shape = androidx.compose.material3.MaterialTheme.shapes.medium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.missing_features_title),
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.missing_features_explanation),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { backStack.pop() }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object MainPage: Route

    @Serializable
    data class UserPage(val id: Long): Route

    @Serializable
    data class UserPageHistoryDatePicker(val initialDate: LocalDate): Route

    @Serializable
    data class WaypointEditPage(val id: Long): Route

    @Serializable
    data class AddPersonDialog(val id: Long? = null): Route

    @Serializable
    data object AddLinkDialog: Route

    @Serializable
    data object MissingFeaturesDialog: Route

    @Serializable
    data object Settings: Route
}

@Composable
fun Navigation(platform: Platform, viewModel: DatabaseViewModel, showMissingFeatures: Boolean) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage)

    LaunchedEffect(showMissingFeatures) {
        if (showMissingFeatures) {
            backStack.add(Route.MissingFeaturesDialog)
        }
    }

    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(platform, backStack, viewModel)
        }
        entry<Route.UserPage> {
            UserPage(platform, backStack, viewModel, it.id)
        }
        entry<Route.WaypointEditPage> {
            WaypointEditPage(backStack, viewModel, it.id)
        }
        entry<Route.UserPageHistoryDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(backStack, "HistoryDatePicker", it.initialDate, maxDate = Clock.System.now().toLocalDateTime(
                TimeZone.currentSystemDefault()).date)
        }
        entry<Route.AddPersonDialog>(metadata = DialogPage()) {
            AddPersonDialog(backStack, viewModel, platform, it.id)
        }
        entry<Route.AddLinkDialog>(metadata = DialogPage()) {
            AddLinkDialog(backStack, viewModel)
        }
        entry<Route.MissingFeaturesDialog>(metadata = DialogPage()) {
            MissingFeaturesDialog(backStack)
        }
        entry<Route.Settings> {
            SettingsPage(backStack, viewModel)
        }
    }
}
