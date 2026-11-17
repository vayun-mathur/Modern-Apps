package com.vayunmathur.flashcards.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.ui.SettingsActions
import com.vayunmathur.flashcards.platform.setDesiredRetentionImpl
import com.vayunmathur.flashcards.platform.setNewPerDayImpl
import com.vayunmathur.flashcards.platform.setMaxReviewsImpl
import com.vayunmathur.flashcards.platform.setThemeModeImpl
import com.vayunmathur.flashcards.platform.setAutoPlayImpl
import com.vayunmathur.flashcards.platform.setReminderEnabledImpl
import com.vayunmathur.flashcards.platform.setReminderTimeImpl
import com.vayunmathur.flashcards.platform.saveDeckPresetImpl
import com.vayunmathur.flashcards.platform.applyDeckPresetImpl
import com.vayunmathur.flashcards.platform.deleteDeckPresetImpl
import com.vayunmathur.flashcards.platform.optimizeAllDecksImpl
import com.vayunmathur.flashcards.platform.exportApkgImpl
import com.vayunmathur.flashcards.ui.SettingsUiState
import com.vayunmathur.flashcards.ui.ThemeMode
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.rememberTimePickerState
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.roundToInt

/** Binds persisted settings to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: FlashcardsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val presets by viewModel.deckPresets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_deck)

    // POST_NOTIFICATIONS is a String constant inlined at compile time, so referencing
    // it below API 33 is safe; it is only requested on API 33+ (see setReminderEnabled).
    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        ""
    }
    val requestNotifications = rememberPermissionRequest(notificationPermission)

    LaunchedEffect(viewModel) {
        viewModel.shareRequests.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, shareTitle),
            )
        }
    }

    val actions = object : SettingsActions {
        override fun back() { backStack.pop() }
        override fun setDesiredRetention(value: Double) { viewModel.setDesiredRetentionImpl(value) }
        override fun setNewPerDay(value: Int) { viewModel.setNewPerDayImpl(value) }
        override fun setMaxReviews(value: Int) { viewModel.setMaxReviewsImpl(value) }
        override fun setThemeMode(mode: Int) { viewModel.setThemeModeImpl(mode) }
        override fun setAutoPlay(enabled: Boolean) { viewModel.setAutoPlayImpl(enabled) }
        override fun setReminderEnabled(enabled: Boolean) {
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications()
            }
            viewModel.setReminderEnabledImpl(enabled)
        }
        override fun setReminderTime(hour: Int, minute: Int) {
            viewModel.setReminderTimeImpl(hour, minute)
        }
        override fun saveDeckPreset(name: String) { viewModel.saveDeckPresetImpl(name) }
        override fun applyDeckPreset(name: String) { viewModel.applyDeckPresetImpl(name) }
        override fun deleteDeckPreset(name: String) { viewModel.deleteDeckPresetImpl(name) }
        override fun optimizeFsrs() { viewModel.optimizeAllDecksImpl() }
        override fun manageNoteTypes() { backStack.add(Route.NoteTypeList) }
        override fun exportCollection() { viewModel.exportApkgImpl(null) }
    }

    SettingsScreen(state = settings.copy(presets = presets), actions = actions)
}
