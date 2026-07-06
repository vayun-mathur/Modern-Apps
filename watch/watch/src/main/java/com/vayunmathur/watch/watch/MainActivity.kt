package com.vayunmathur.watch.watch

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.watch.watch.data.SensorDatabase
import com.vayunmathur.watch.watch.data.WorkoutType
import com.vayunmathur.watch.watch.service.ExerciseService
import com.vayunmathur.watch.watch.service.SensorBackgroundService

class MainActivity : ComponentActivity() {

    private var openPickerFromTile by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme {
                RootScreen(openPickerRequested = openPickerFromTile, onPickerConsumed = {
                    openPickerFromTile = false
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_PICKER) != null) {
            openPickerFromTile = true
        }
    }

    private enum class Screen { Home, Picker, Live }

    @Composable
    private fun RootScreen(openPickerRequested: Boolean, onPickerConsumed: () -> Unit) {
        val context = this
        var screen by remember { mutableStateOf(Screen.Home) }

        val exerciseState by ExerciseService.uiStateFlow.collectAsState()

        var hasCollectorPerms by remember { mutableStateOf(false) }
        var hasExercisePerms by remember { mutableStateOf(false) }

        val requestCollectorPerms = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result -> hasCollectorPerms = result.values.all { it } },
        )
        val requestExercisePerms = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
            onResult = { result -> hasExercisePerms = result.values.all { it } },
        )

        LaunchedEffect(Unit) {
            hasCollectorPerms = collectorPermissions().all { granted(it) }
            hasExercisePerms = exercisePermissions().all { granted(it) }
        }

        // Route to the picker when launched from the Tile.
        LaunchedEffect(openPickerRequested) {
            if (openPickerRequested) {
                screen = Screen.Picker
                onPickerConsumed()
            }
        }

        // Follow the session: show live metrics whenever a workout is running.
        LaunchedEffect(exerciseState) {
            when (exerciseState) {
                ExerciseService.UiState.Preparing,
                ExerciseService.UiState.Active,
                ExerciseService.UiState.Paused,
                -> screen = Screen.Live
                ExerciseService.UiState.Ended -> if (screen == Screen.Live) screen = Screen.Home
                ExerciseService.UiState.Idle -> Unit
            }
        }

        when (screen) {
            Screen.Home -> HomeScreen(
                hasCollectorPerms = hasCollectorPerms,
                onGrantCollector = { requestCollectorPerms.launch(collectorPermissions()) },
                onOpenPicker = {
                    if (hasExercisePerms) screen = Screen.Picker
                    else requestExercisePerms.launch(exercisePermissions())
                },
            )
            Screen.Picker -> PickerScreen(
                onPick = { workout ->
                    ExerciseService.start(context, workout)
                    screen = Screen.Live
                },
                onBack = { screen = Screen.Home },
            )
            Screen.Live -> LiveScreen(
                state = exerciseState,
                onPause = { ExerciseService.pause(context) },
                onResume = { ExerciseService.resume(context) },
                onStop = { ExerciseService.stop(context) },
            )
        }
    }

    @Composable
    private fun HomeScreen(
        hasCollectorPerms: Boolean,
        onGrantCollector: () -> Unit,
        onOpenPicker: () -> Unit,
    ) {
        val context = this
        val dao = remember { SensorDatabase.get(context).sensorDao() }
        val rowCount by dao.countFlow().collectAsState(0)
        var collecting by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!hasCollectorPerms) {
                Chip(
                    label = { Text(stringResource(R.string.grant_permissions)) },
                    onClick = onGrantCollector,
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            val onOff = if (collecting) stringResource(R.string.on) else stringResource(R.string.off)
            Text(stringResource(R.string.status_collecting, onOff))
            Text(stringResource(R.string.status_rows, rowCount))
            Chip(
                label = {
                    Text(
                        if (collecting) stringResource(R.string.action_stop)
                        else stringResource(R.string.action_start),
                    )
                },
                onClick = {
                    collecting = if (collecting) {
                        SensorBackgroundService.stop(context)
                        false
                    } else {
                        SensorBackgroundService.start(context)
                        true
                    }
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Chip(
                label = { Text(stringResource(R.string.exercise_start_workout)) },
                onClick = onOpenPicker,
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            val notificationManager = remember {
                context.getSystemService(NotificationManager::class.java)
            }
            var hasDndAccess by remember {
                mutableStateOf(notificationManager.isNotificationPolicyAccessGranted)
            }
            if (!hasDndAccess) {
                Chip(
                    label = { Text(stringResource(R.string.grant_dnd_access)) },
                    onClick = {
                        openDndAccessSettings()
                        hasDndAccess = notificationManager.isNotificationPolicyAccessGranted
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun PickerScreen(onPick: (WorkoutType) -> Unit, onBack: () -> Unit) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.exercise_pick_type),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            items(WorkoutType.entries) { workout ->
                Chip(
                    label = { Text(workout.label) },
                    onClick = { onPick(workout) },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    label = { Text(stringResource(R.string.exercise_back)) },
                    onClick = onBack,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun LiveScreen(
        state: ExerciseService.UiState,
        onPause: () -> Unit,
        onResume: () -> Unit,
        onStop: () -> Unit,
    ) {
        val durationMs by ExerciseService.activeDurationFlow.collectAsState()
        val hr by ExerciseService.heartRateFlow.collectAsState()
        val calories by ExerciseService.caloriesFlow.collectAsState()
        val distance by ExerciseService.distanceFlow.collectAsState()
        val label by ExerciseService.activeWorkoutLabelFlow.collectAsState()
        val availability by ExerciseService.availabilityFlow.collectAsState()
        val message by ExerciseService.messageFlow.collectAsState()
        val dash = stringResource(R.string.exercise_dash)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label ?: stringResource(R.string.exercise_title))
            Text(stringResource(R.string.exercise_state, state.name))
            Text(stringResource(R.string.exercise_duration, formatDuration(durationMs)))
            Text(stringResource(R.string.exercise_hr, hr?.toInt()?.toString() ?: dash))
            Text(stringResource(R.string.exercise_calories, calories?.toInt()?.toString() ?: dash))
            Text(stringResource(R.string.exercise_distance, distance?.toInt()?.toString() ?: dash))
            availability?.let { Text(it) }
            message?.let { Text(it) }

            if (state == ExerciseService.UiState.Paused) {
                Chip(
                    label = { Text(stringResource(R.string.exercise_resume)) },
                    onClick = onResume,
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else {
                Chip(
                    label = { Text(stringResource(R.string.exercise_pause)) },
                    onClick = onPause,
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Chip(
                label = { Text(stringResource(R.string.exercise_stop)) },
                onClick = onStop,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    // Wear OS has no dedicated notification-policy-access screen, so try that
    // action first and fall back to the general Settings app.
    private fun openDndAccessSettings() {
        val actions = listOf(
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in actions) {
            if (runCatching { startActivity(Intent(action)) }.isSuccess) return
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun collectorPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            heartRatePermission(),
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    // Active exercise needs body sensors + activity recognition (HR/steps) and,
    // for outdoor GPS routes, fine location.
    private fun exercisePermissions(): Array<String> {
        val perms = mutableListOf(
            heartRatePermission(),
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    // Android 16 (API 36) removed BODY_SENSORS for apps targeting 36+; heart-rate
    // sensor access moved to the granular health permission.
    private fun heartRatePermission(): String =
        if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEART_RATE"
        } else {
            Manifest.permission.BODY_SENSORS
        }

    companion object {
        const val EXTRA_OPEN_PICKER = "open_picker"
    }
}
