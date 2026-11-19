package com.vayunmathur.games.nonogram.ui

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.nonogram.platform.NonogramViewModel
import com.vayunmathur.games.nonogram.platform.SettingsActions
import com.vayunmathur.games.nonogram.platform.SettingsUiState
import com.vayunmathur.library.ui.DailyReminderSettingsSection
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberPermissionRequest

/** Binds [NonogramViewModel] to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(viewModel: NonogramViewModel, onBack: () -> Unit) {
    val reminderEnabled by viewModel.reminderEnabled.collectAsState()
    val reminderMinutes by viewModel.reminderMinutesOfDay.collectAsState()
    // POST_NOTIFICATIONS is a String constant inlined at compile time, so referencing
    // it below API 33 is safe; it is only requested on API 33+ (see setReminderEnabled).
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        ""
    }
    val requestNotifications = rememberPermissionRequest(notificationPermission)

    SettingsScreen(
        state = SettingsUiState(
            reminderEnabled = reminderEnabled,
            reminderHour = (reminderMinutes / 60).toInt(),
            reminderMinute = (reminderMinutes % 60).toInt(),
        ),
        actions = object : SettingsActions {
            override fun setReminderEnabled(enabled: Boolean) {
                // Asked for lazily, on opt-in only, so the screen never blocks on a prompt.
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications()
                }
                viewModel.setReminderEnabled(enabled)
            }

            override fun setReminderTime(hour: Int, minute: Int) =
                viewModel.setReminderTime(hour, minute)
        },
        onBack = onBack,
    )
}

/** The settings screen, ViewModel-free so a preview can render it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, onBack: () -> Unit) {
    DetailScaffold(
        title = stringResource(UiR.string.settings),
        onNavigateBack = onBack,
    ) {
        DailyReminderSettingsSection(
            enabled = state.reminderEnabled,
            hour = state.reminderHour,
            minute = state.reminderMinute,
            onEnabledChange = { actions.setReminderEnabled(it) },
            onTimeChange = { hour, minute -> actions.setReminderTime(hour, minute) },
        )
    }
}
