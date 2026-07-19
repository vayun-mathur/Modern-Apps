package com.vayunmathur.findfamily

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueDao
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.TemporaryLinkDao
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserDao
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.data.WaypointDao
import com.vayunmathur.findfamily.ui.MainPage
import com.vayunmathur.findfamily.ui.UwbRangingScreen
import com.vayunmathur.findfamily.ui.dialogs.AddLinkDialog
import com.vayunmathur.findfamily.ui.dialogs.AddPersonDialog
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.dialog.DatePickerDialog
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import com.vayunmathur.findfamily.util.FindFamilyViewModel
import com.vayunmathur.findfamily.util.FindFamilyViewModelFactory
import com.vayunmathur.findfamily.util.Platform

class MainActivity : ComponentActivity() {
    companion object {
        /** Extra key for launching directly into [Route.UwbRangingPage] via notification tap. */
        const val EXTRA_UWB_PEER_ID = "com.vayunmathur.findfamily.EXTRA_UWB_PEER_ID"
    }

    private lateinit var userDao: UserDao
    private lateinit var waypointDao: WaypointDao
    private lateinit var locationValueDao: LocationValueDao
    private lateinit var temporaryLinkDao: TemporaryLinkDao
    private val ffViewModel: FindFamilyViewModel by viewModels {
        FindFamilyViewModelFactory(application, userDao, waypointDao, locationValueDao, temporaryLinkDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = buildDatabase<FFDatabase>()
        userDao = db.userDao()
        waypointDao = db.waypointDao()
        locationValueDao = db.locationValueDao()
        temporaryLinkDao = db.temporaryLinkDao()
        val platform = Platform(this)
        setContent {
            DynamicTheme {
                val hasForeground by ffViewModel.hasForeground.collectAsState()
                val hasCoarse by ffViewModel.hasCoarse.collectAsState()
                val hasBackground by ffViewModel.hasBackground.collectAsState()

                // Automatically re-check when returning from System Settings
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            ffViewModel.refreshPermissions()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (!hasForeground || !hasBackground) {
                    NoPermissionsScreen(
                        hasFine = hasForeground,
                        hasCoarse = hasCoarse,
                        hasBackground = hasBackground,
                        onPermissionsChanged = { ffViewModel.refreshPermissions() }
                    )
                } else {
                    val deepLinkPeerId = remember {
                        intent?.takeIf { it.hasExtra(EXTRA_UWB_PEER_ID) }
                            ?.getLongExtra(EXTRA_UWB_PEER_ID, -1L)
                            ?.takeIf { it != -1L }
                    }
                    Navigation(platform, ffViewModel, ffViewModel.missingFeatures, deepLinkPeerId)
                }
            }
        }
    }
}

@Composable
fun NoPermissionsScreen(
    hasFine: Boolean,
    hasCoarse: Boolean,
    hasBackground: Boolean,
    onPermissionsChanged: () -> Unit
) {
    // The user granted location but only at an approximate (coarse) level.
    // FindFamily requires precise (fine) location.
    val coarseOnly = hasCoarse && !hasFine

    var showUpgradeDialog by remember { mutableStateOf(false) }

    // Request fine AND coarse together. On Android 12+ this is what surfaces the
    // Precise/Approximate choice; requesting fine alone is ignored by the system.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        onPermissionsChanged()
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        // Approximate-only: prompt the user to upgrade to precise.
        if (!fineGranted && coarseGranted) {
            showUpgradeDialog = true
        }
    }

    // Launcher for Background Location (Redirects to Settings)
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onPermissionsChanged() }

    // Auto-surface the upgrade prompt when we first detect approximate-only.
    // It re-fires only when coarseOnly transitions to true, so dismissing it
    // doesn't immediately reopen it — the explicit button below re-opens it.
    LaunchedEffect(coarseOnly) {
        if (coarseOnly) showUpgradeDialog = true
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // STEP 1: Precise (Fine) Location
            Button(
                onClick = {
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                enabled = !hasFine
            ) {
                Text(if (hasFine) stringResource(R.string.permission_fine_location_granted) else stringResource(R.string.permission_grant_fine_location))
            }

            // Approximate-only: explain and offer a reliable way to re-open the
            // upgrade prompt after it has been dismissed.
            if (coarseOnly) {
                Text(
                    text = stringResource(R.string.permission_approximate_only_explanation),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
                Button(onClick = { showUpgradeDialog = true }) {
                    Text(stringResource(R.string.permission_reopen_upgrade_prompt))
                }
            }

            Spacer(Modifier.height(16.dp))

            // STEP 2: Background Location
            Button(
                onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                enabled = hasFine && !hasBackground
            ) {
                val label = if (hasBackground) stringResource(R.string.permission_background_granted) else stringResource(R.string.permission_enable_all_the_time)
                Text(label)
            }

            if (hasFine && !hasBackground) {
                Text(
                    text = stringResource(R.string.permission_background_explanation),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            title = { Text(stringResource(R.string.permission_upgrade_dialog_title)) },
            text = { Text(stringResource(R.string.permission_upgrade_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showUpgradeDialog = false
                    locationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) {
                    Text(stringResource(R.string.permission_upgrade_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpgradeDialog = false }) {
                    Text(stringResource(R.string.permission_upgrade_dialog_dismiss))
                }
            }
        )
    }
}

@Composable
fun MissingFeaturesDialog(backStack: NavBackStack<Route>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.missing_features_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.missing_features_explanation),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
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
    data class MainPage(val selectedUserId: Long? = null, val selectedWaypointId: Long? = null): Route

    @Serializable
    data class UserPageHistoryDatePicker(val initialDate: LocalDate): Route

    @Serializable
    data class AddPersonDialog(val id: Long? = null): Route

    @Serializable
    data object AddLinkDialog: Route

    @Serializable
    data object MissingFeaturesDialog: Route

    /** UWB Find Nearby (UWB) screen for the given peer. Full screen (no DialogPage metadata). */
    @Serializable
    data class UwbRangingPage(val userId: Long): Route
}

@Composable
fun Navigation(
    platform: Platform,
    ffViewModel: FindFamilyViewModel,
    showMissingFeatures: Boolean,
    deepLinkUwbPeerId: Long? = null,
) {
    val backStack = rememberNavBackStack<Route>(Route.MainPage())

    LaunchedEffect(showMissingFeatures) {
        if (showMissingFeatures) {
            backStack.add(Route.MissingFeaturesDialog)
        }
    }

    LaunchedEffect(deepLinkUwbPeerId) {
        if (deepLinkUwbPeerId != null) {
            backStack.add(Route.UwbRangingPage(deepLinkUwbPeerId))
        }
    }

    MainNavigation(backStack) {
        entry<Route.MainPage> {
            MainPage(platform, backStack, ffViewModel, it.selectedUserId, it.selectedWaypointId)
        }
        entry<Route.UserPageHistoryDatePicker>(metadata = DialogPage()) {
            DatePickerDialog(backStack, "HistoryDatePicker", it.initialDate, maxDate = Clock.System.now().toLocalDateTime(
                TimeZone.currentSystemDefault()).date)
        }
        entry<Route.AddPersonDialog>(metadata = DialogPage()) {
            AddPersonDialog(backStack, ffViewModel, platform, it.id)
        }
        entry<Route.AddLinkDialog>(metadata = DialogPage()) {
            AddLinkDialog(backStack, ffViewModel)
        }
        entry<Route.MissingFeaturesDialog>(metadata = DialogPage()) {
            MissingFeaturesDialog(backStack)
        }
        entry<Route.UwbRangingPage> {
            UwbRangingScreen(backStack, ffViewModel, it.userId)
        }
    }
}
