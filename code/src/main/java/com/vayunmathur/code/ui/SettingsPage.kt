package com.vayunmathur.code.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.code.Route
import com.vayunmathur.code.domain.syntax.EditorThemes
import com.vayunmathur.code.data.EditorPrefs
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.roundToInt

/**
 * Editor preferences: appearance (theme) and editor behaviour (font size, tab width, the
 * smart-input toggles and soft-wrap default). Each control writes straight through the
 * ViewModel, which persists it and updates the observable state the editor reads.
 */
@Composable
fun SettingsPage(viewModel: EditorViewModel, backStack: NavBackStack<Route>) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showTabWidthDialog by remember { mutableStateOf(false) }
    var showEditorThemeDialog by remember { mutableStateOf(false) }

    AppScaffold(title = stringResource(UiR.string.settings), backStack = backStack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(title = stringResource(R.string.appearance)) {
                SettingsRow(
                    title = stringResource(R.string.theme),
                    supportingText = themeLabel(viewModel.themeMode),
                    onClick = { showThemeDialog = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.editor_theme),
                    supportingText = editorThemeLabel(viewModel.editorTheme),
                    onClick = { showEditorThemeDialog = true },
                )
            }

            SettingsSection(title = stringResource(R.string.editor)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.font_size) + ": ${viewModel.fontSize}sp",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        value = viewModel.fontSize.toFloat(),
                        onValueChange = { viewModel.actions.setFontSize(it.roundToInt()) },
                        valueRange = MIN_FONT_SIZE.toFloat()..MAX_FONT_SIZE.toFloat(),
                        steps = MAX_FONT_SIZE - MIN_FONT_SIZE - 1,
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = stringResource(R.string.tab_width),
                    supportingText = viewModel.tabWidth.toString(),
                    onClick = { showTabWidthDialog = true },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.auto_indent),
                    supportingText = stringResource(R.string.auto_indent_desc),
                    checked = viewModel.autoIndent,
                    onCheckedChange = viewModel.actions::setAutoIndent,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.auto_close_brackets),
                    supportingText = stringResource(R.string.auto_close_brackets_desc),
                    checked = viewModel.autoCloseBrackets,
                    onCheckedChange = viewModel.actions::setAutoCloseBrackets,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.soft_wrap),
                    supportingText = stringResource(R.string.soft_wrap_desc),
                    checked = viewModel.softWrap,
                    onCheckedChange = { viewModel.actions.toggleSoftWrap() },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.auto_save),
                    supportingText = stringResource(R.string.auto_save_desc),
                    checked = viewModel.autoSave,
                    onCheckedChange = viewModel::setAutoSave,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.trim_trailing_whitespace),
                    supportingText = stringResource(R.string.trim_trailing_whitespace_desc),
                    checked = viewModel.trimTrailingOnSave,
                    onCheckedChange = viewModel::setTrimTrailingOnSave,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.final_newline),
                    supportingText = stringResource(R.string.final_newline_desc),
                    checked = viewModel.finalNewlineOnSave,
                    onCheckedChange = viewModel::setFinalNewlineOnSave,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.experimental_editor),
                    supportingText = stringResource(R.string.experimental_editor_desc),
                    checked = viewModel.experimentalEditor,
                    onCheckedChange = viewModel::setExperimentalEditor,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.show_whitespace),
                    supportingText = stringResource(R.string.show_whitespace_desc),
                    checked = viewModel.showWhitespace,
                    onCheckedChange = viewModel::setShowWhitespace,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.show_indent_guides),
                    supportingText = stringResource(R.string.show_indent_guides_desc),
                    checked = viewModel.showIndentGuides,
                    onCheckedChange = viewModel::setShowIndentGuides,
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.show_minimap),
                    supportingText = stringResource(R.string.show_minimap_desc),
                    checked = viewModel.showMinimap,
                    onCheckedChange = viewModel::setShowMinimap,
                )
            }

            SettingsSection(title = stringResource(R.string.user_snippets)) {
                SettingsRow(
                    title = stringResource(R.string.manage_snippets),
                    supportingText = viewModel.userSnippets.size.toString(),
                    onClick = { backStack.add(Route.Snippets) },
                )
            }
        }
    }

    if (showThemeDialog) {
        val modes = listOf(
            EditorPrefs.THEME_SYSTEM to stringResource(R.string.theme_system),
            EditorPrefs.THEME_LIGHT to stringResource(R.string.theme_light),
            EditorPrefs.THEME_DARK to stringResource(R.string.theme_dark),
        )
        ChoiceDialog(
            title = stringResource(R.string.theme),
            options = modes,
            selected = viewModel.themeMode,
            onSelect = { viewModel.actions.setThemeMode(it) },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showTabWidthDialog) {
        val widths = listOf(2, 4, 8).map { it to it.toString() }
        ChoiceDialog(
            title = stringResource(R.string.tab_width),
            options = widths,
            selected = viewModel.tabWidth,
            onSelect = { viewModel.actions.setTabWidth(it) },
            onDismiss = { showTabWidthDialog = false },
        )
    }

    if (showEditorThemeDialog) {
        ChoiceDialog(
            title = stringResource(R.string.editor_theme),
            options = EditorThemes.ALL,
            selected = viewModel.editorTheme,
            onSelect = viewModel::setEditorTheme,
            onDismiss = { showEditorThemeDialog = false },
        )
    }
}

@Composable
private fun themeLabel(mode: String): String = when (mode) {
    EditorPrefs.THEME_LIGHT -> stringResource(R.string.theme_light)
    EditorPrefs.THEME_DARK -> stringResource(R.string.theme_dark)
    else -> stringResource(R.string.theme_system)
}

private fun editorThemeLabel(theme: String): String =
    EditorThemes.ALL.firstOrNull { it.first == theme }?.second ?: theme

/** A single-choice list dialog of radio rows; selecting a value applies it and closes. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == value,
                            onClick = { onSelect(value); onDismiss() },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.close)) }
        },
    )
}

private const val MIN_FONT_SIZE = 10
private const val MAX_FONT_SIZE = 24
