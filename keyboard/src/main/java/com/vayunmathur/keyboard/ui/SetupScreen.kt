package com.vayunmathur.keyboard.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.keyboard.R
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.keyboard.domain.KeyboardLayouts
import com.vayunmathur.keyboard.data.KeyboardSettings
import com.vayunmathur.keyboard.platform.ime.KeyboardService
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.launch

/**
 * Setup flow + live settings. Shows whether the IME is enabled/selected, offers the two
 * system shortcuts to do so, exposes every preference (persisted to DataStore, read live by
 * the service), and provides a field to try the keyboard immediately.
 */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val ds = remember { DataStoreUtils.getInstance(context, deviceProtected = true) }
    val scope = rememberCoroutineScope()
    val imm = remember { context.getSystemService(InputMethodManager::class.java) }

    fun isEnabled(): Boolean =
        imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true

    fun isSelected(): Boolean {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return id != null && id.contains(context.packageName)
    }

    var enabled by remember { mutableStateOf(isEnabled()) }
    var selected by remember { mutableStateOf(isSelected()) }

    // Re-check status whenever we return from the system IME settings / picker.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = isEnabled()
                selected = isSelected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val keys = KeyboardSettings.Keys
    var haptic by remember { mutableStateOf(ds.getBoolean(keys.HAPTIC, true)) }
    var sound by remember { mutableStateOf(ds.getBoolean(keys.SOUND, false)) }
    var autoCap by remember { mutableStateOf(ds.getBoolean(keys.AUTO_CAP, true)) }
    var doubleSpace by remember { mutableStateOf(ds.getBoolean(keys.DOUBLE_SPACE_PERIOD, true)) }
    var showSuggestions by remember { mutableStateOf(ds.getBoolean(keys.SHOW_SUGGESTIONS, true)) }
    var autoCorrect by remember { mutableStateOf(ds.getBoolean(keys.AUTO_CORRECT, false)) }
    var numberRow by remember { mutableStateOf(ds.getBoolean(keys.NUMBER_ROW, true)) }
    var clipboardEnabled by remember { mutableStateOf(ds.getBoolean(keys.CLIPBOARD, true)) }
    var keyHeight by remember { mutableFloatStateOf((ds.getDouble(keys.KEY_HEIGHT) ?: 1.0).toFloat()) }

    // Read once at open; the note under Suggestions reflects the layout active then.
    val activeLayoutId = remember { ds.getString(keys.ACTIVE_LAYOUT) ?: KeyboardLayouts.DEFAULT.id }

    // This IME's framework id, for deep-linking to its own language/subtype settings.
    val imeId = remember {
        imm?.enabledInputMethodList?.firstOrNull { it.packageName == context.packageName }?.id
            ?: ComponentName(context, KeyboardService::class.java).flattenToString()
    }

    var testText by remember { mutableStateOf("") }

    DetailScaffold(
        title = stringResource(R.string.app_name),
        alignment = AppBarAlignment.Center,
    ) {
            StatusCard(enabled = enabled, selected = selected)

            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.enable_keyboard)) }

            OutlinedButton(
                onClick = { imm?.showInputMethodPicker() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.choose_keyboard)) }

            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS)
                        .putExtra(Settings.EXTRA_INPUT_METHOD_ID, imeId)
                    if (!ExternalIntents.launch(context, intent)) {
                        ExternalIntents.launch(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.keyboard_languages)) }

            HorizontalDivider()

            Text(stringResource(UiR.string.settings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            SettingSwitch(stringResource(R.string.setting_haptic_feedback), haptic) {
                haptic = it; scope.launch { ds.setBoolean(keys.HAPTIC, it) }
            }
            SettingSwitch(stringResource(R.string.setting_key_sound), sound) {
                sound = it; scope.launch { ds.setBoolean(keys.SOUND, it) }
            }
            SettingSwitch(stringResource(R.string.setting_auto_capitalize), autoCap) {
                autoCap = it; scope.launch { ds.setBoolean(keys.AUTO_CAP, it) }
            }
            SettingSwitch(stringResource(R.string.setting_double_space_period), doubleSpace) {
                doubleSpace = it; scope.launch { ds.setBoolean(keys.DOUBLE_SPACE_PERIOD, it) }
            }
            // The word list we ship is English, so say so rather than let these two look
            // broken while a Greek or Thai layout is active.
            val englishOnly = KeyboardLayouts.byId(activeLayoutId)?.englishDictionary == false
            val englishNote =
                if (englishOnly) stringResource(R.string.available_for_english_layouts_only) else null
            SettingSwitch(stringResource(R.string.setting_show_suggestions), showSuggestions, englishNote) {
                showSuggestions = it; scope.launch { ds.setBoolean(keys.SHOW_SUGGESTIONS, it) }
            }
            SettingSwitch(stringResource(R.string.setting_auto_correct), autoCorrect, englishNote) {
                autoCorrect = it; scope.launch { ds.setBoolean(keys.AUTO_CORRECT, it) }
            }
            SettingSwitch(stringResource(R.string.setting_number_row), numberRow) {
                numberRow = it; scope.launch { ds.setBoolean(keys.NUMBER_ROW, it) }
            }
            SettingSwitch(
                stringResource(R.string.setting_clipboard_history),
                clipboardEnabled,
                stringResource(R.string.setting_clipboard_history_desc),
            ) {
                clipboardEnabled = it
                scope.launch { ds.setBoolean(keys.CLIPBOARD, it) }
            }
            if (clipboardEnabled) {
                // Bumping the wipe counter is the signal the running IME watches for; it
                // wipes its in-memory history (including the sensitive clips that never
                // reached disk) rather than letting this write be overwritten. A counter
                // rather than a blanked clips string, so the startup migration clearing
                // the legacy key cannot be mistaken for a wipe request.
                SettingsRow(
                    title = stringResource(R.string.clear_clipboard_history),
                    onClick = {
                        scope.launch {
                            ds.setLong(keys.CLIPS_WIPE, (ds.getLong(keys.CLIPS_WIPE) ?: 0L) + 1)
                        }
                    },
                    leadingContent = { IconDelete() },
                )
            }

            Column {
                Text(stringResource(R.string.key_height), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = keyHeight,
                    onValueChange = { keyHeight = it },
                    valueRange = 0.8f..1.4f,
                    onValueChangeFinished = {
                        scope.launch { ds.setDouble(keys.KEY_HEIGHT, keyHeight.toDouble()) }
                    },
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.theme)) },
                supportingContent = { Text(stringResource(R.string.follows_the_system_light_dark_theme_and)) },
            )

            HorizontalDivider()

            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it },
                label = { Text(stringResource(R.string.try_the_keyboard_here)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            )
    }
}

@Composable
private fun StatusCard(enabled: Boolean, selected: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusRow(stringResource(R.string.status_enabled), enabled)
            StatusRow(stringResource(R.string.status_selected), selected)
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ok) {
            IconCheck(tint = MaterialTheme.colorScheme.primary)
        } else {
            IconClose(tint = MaterialTheme.colorScheme.error)
        }
        Text(label)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    supportingText: String? = null,
    onChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = title,
        checked = checked,
        onCheckedChange = onChange,
        supportingText = supportingText,
    )
}
