package com.vayunmathur.findfamily

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.migration.Migration
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.ui.MainPage
import com.vayunmathur.findfamily.ui.UserPage
import com.vayunmathur.findfamily.ui.WaypointEditPage
import com.vayunmathur.findfamily.ui.dialog.AddLinkDialog
import com.vayunmathur.findfamily.ui.dialog.AddPersonDialog
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<FFDatabase>(listOf(Migration_1_2))
        val viewModel = DatabaseViewModel(db, User::class to db.userDao(), Waypoint::class to db.waypointDao(), LocationValue::class to db.locationValueDao(), TemporaryLink::class to db.temporaryLinkDao())
        val platform = Platform(this)
        setContent {
            DynamicTheme {
                val context = LocalContext.current
                val foregroundPermission = Manifest.permission.ACCESS_FINE_LOCATION
                val backgroundPermission = Manifest.permission.ACCESS_BACKGROUND_LOCATION

                var hasForeground by remember {
                    mutableStateOf(ContextCompat.checkSelfPermission(context, foregroundPermission) == PackageManager.PERMISSION_GRANTED)
                }
                var hasBackground by remember {
                    mutableStateOf(ContextCompat.checkSelfPermission(context, backgroundPermission) == PackageManager.PERMISSION_GRANTED)
                }

                // Automatically re-check when returning from System Settings
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            hasForeground = ContextCompat.checkSelfPermission(
                                context,
                                foregroundPermission
                            ) == PackageManager.PERMISSION_GRANTED
                            hasBackground = ContextCompat.checkSelfPermission(
                                context,
                                backgroundPermission
                            ) == PackageManager.PERMISSION_GRANTED
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (!hasForeground || !hasBackground) {
                    NoPermissionsScreen(
                        hasForeground = hasForeground,
                        hasBackground = hasBackground,
                        onForegroundGranted = { hasForeground = true },
                        onBackgroundGranted = { hasBackground = true }
                    )
                } else {
                    Main(platform, viewModel)
                }
            }
        }
    }

    @Composable
    fun Main(platform: Platform, viewModel: DatabaseViewModel) {
        LaunchedEffect(Unit) {
            ensureSync(this@MainActivity)
        }
        Navigation(platform, viewModel)
    }
}

val Migration_1_2 = Migration(1, 2) {
    it.execSQL("CREATE INDEX IF NOT EXISTS index_LocationValue_timestamp ON LocationValue (timestamp)")
}

@Composable
fun NoPermissionsScreen(
    hasForeground: Boolean,
    hasBackground: Boolean,
    onForegroundGranted: () -> Unit,
    onBackgroundGranted: () -> Unit
) {
    // Launcher for Fine Location
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) onForegroundGranted()
    }

    // Launcher for Background Location (Redirects to Settings)
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) onBackgroundGranted()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // STEP 1: Fine Location
            Button(
                onClick = { foregroundLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                enabled = !hasForeground
            ) {
                Text(if (hasForeground) "✅ Fine Location Granted" else "1. Grant Fine Location")
            }

            Spacer(Modifier.height(16.dp))

            // STEP 2: Background Location
            Button(
                onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                enabled = hasForeground && !hasBackground
            ) {
                val label = if (hasBackground) "✅ Background Granted" else "2. Enable 'Allow all the time'"
                Text(label)
            }

            if (hasForeground && !hasBackground) {
                Text(
                    text = "To enable background tracking, please select 'Allow all the time' in the next screen.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
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
}

@Composable
fun Navigation(platform: Platform, viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage)
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
    }
}