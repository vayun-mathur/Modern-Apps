package com.vayunmathur.clock.ui
import android.media.RingtoneManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.ui.components.AlarmOptionControls
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.ringtonePickerIntent
import com.vayunmathur.library.ui.ringtonePickerResult
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmSettingsPage(backStack: NavBackStack<Route>, ds: DataStoreUtils) {
    val scope = rememberCoroutineScope()

    var ringtone by remember { mutableStateOf(ds.getString(ClockViewModel.KEY_DEFAULT_RINGTONE)) }
    var vibrate by remember { mutableStateOf(ds.getBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, true)) }
    var snooze by remember { mutableIntStateOf((ds.getLong(ClockViewModel.KEY_DEFAULT_SNOOZE) ?: 5L).toInt()) }
    var gradual by remember { mutableIntStateOf((ds.getLong(ClockViewModel.KEY_DEFAULT_GRADUAL) ?: 0L).toInt()) }
    val soundTitle = stringResource(R.string.sound)

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val picked = ringtonePickerResult(result.data)
            ringtone = picked
            scope.launch { ds.setString(ClockViewModel.KEY_DEFAULT_RINGTONE, picked) }
        }
    }

    DetailScaffold(
        title = stringResource(R.string.alarm_settings),
        onNavigateBack = { backStack.pop() },
    ) {
        Text(
            stringResource(R.string.defaults_for_new_alarms),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        AlarmOptionControls(
            ringtoneUri = ringtone,
            vibrate = vibrate,
            snoozeMinutes = snooze,
            gradualVolumeSeconds = gradual,
            onRingtoneClick = {
                ringtoneLauncher.launch(
                    ringtonePickerIntent(
                        ringtone,
                        RingtoneManager.TYPE_ALARM,
                        soundTitle,
                    )
                )
            },
            onVibrateChange = {
                vibrate = it
                scope.launch { ds.setBoolean(ClockViewModel.KEY_DEFAULT_VIBRATE, it) }
            },
            onSnoozeChange = {
                snooze = it
                scope.launch { ds.setLong(ClockViewModel.KEY_DEFAULT_SNOOZE, it.toLong()) }
            },
            onGradualChange = {
                gradual = it
                scope.launch { ds.setLong(ClockViewModel.KEY_DEFAULT_GRADUAL, it.toLong()) }
            },
        )
    }
}
