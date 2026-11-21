package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.sharedText

/**
 * The rows every settings screen is built from.
 *
 * Eighteen apps had a settings screen and all of them assembled the same three
 * primitives by hand - a [ListItem], sometimes a [Switch], and a
 * [HorizontalDivider] - which is why the padding, the divider inset and
 * whether the whole row or only the switch was tappable all drifted apart.
 *
 * These are deliberately thin. A settings screen is a list of rows in
 * sections; anything more elaborate than that should still be written by hand
 * rather than bent into these.
 */

/**
 * A titled group of settings rows.
 *
 * Pass [title] as null for an untitled leading group, which reads better than
 * inventing a heading for the two or three rows at the top of a screen.
 */
@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        content()
    }
}

/**
 * A settings row: a title, optional supporting text, and optional leading and
 * trailing content.
 *
 * [onClick] is optional because plenty of rows only display a value. When it
 * is null the row is not clickable at all, rather than clickable with a no-op,
 * so it does not ripple or claim to be interactive.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    /**
     * Morphs this row's title into the same key on the screen it opens. Null on a row that is not
     * the origin of one - which is nearly all of them.
     */
    titleSharedKey: Any? = null,
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    ListItem(
        headlineContent = {
            Text(
                title,
                color = contentColor,
                modifier = if (titleSharedKey == null) Modifier else Modifier.sharedText(titleSharedKey),
            )
        },
        supportingContent = supportingText?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
                )
            }
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null && enabled) modifier.clickable(onClick = onClick) else modifier,
    )
}

/**
 * A settings row that toggles something.
 *
 * The whole row toggles, not just the switch - a switch is a small target and
 * every app that hand-rolled this made a different call about it.
 *
 * Accessibility: the row itself is the single [Role.Switch] toggleable. Its
 * `toggleable` semantics merge descendants, so the title (and supporting
 * text) and the checked state live on one semantics node and TalkBack
 * announces e.g. "Night mode, Switch, on" as one item instead of focusing
 * the bare switch and reading only "Switch, on". The trailing [Switch] is
 * visual only (`onCheckedChange = null`): it contributes no toggleable or
 * click handler of its own, so a tap anywhere on the row — the label area
 * or the switch thumb/track — fires [onCheckedChange] exactly once and
 * cannot double-toggle.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    SettingsRow(
        title = title,
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        supportingText = supportingText,
        enabled = enabled,
        onClick = null,
        leadingContent = leadingContent,
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

/**
 * The reset-to-defaults row, for the bottom of a settings screen.
 *
 * Shows the shared reset row; tapping it asks for confirmation with the
 * shared [ConfirmDialog] (the AlertDialog pattern every destructive action
 * uses) and only then calls [onReset]. The caller owns what "defaults" means
 * for its app — this row deliberately knows no preference keys, so an app
 * can exclude secrets or user data from its reset without this component
 * needing to know.
 */
@Composable
fun ResetDefaultsRow(
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    var showConfirm by remember { mutableStateOf(false) }
    SettingsRow(
        title = stringResource(R.string.reset),
        modifier = modifier,
        supportingText = supportingText,
        enabled = enabled,
        onClick = { showConfirm = true },
    )
    if (showConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.reset),
            message = stringResource(R.string.reset_defaults_message),
            confirmLabel = stringResource(R.string.reset),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = onReset,
            onDismiss = { showConfirm = false },
        )
    }
}

/**
 * Divider between settings rows, inset past any leading icon so it lines up
 * with the text rather than cutting the full width.
 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier, inset: Boolean = true) {
    HorizontalDivider(
        modifier = if (inset) modifier.padding(horizontal = 16.dp) else modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * A settings row that picks one of [options].
 *
 * Reads as a [SettingsRow] whose supporting text is the current selection and
 * whose trailing affordance is a dropdown arrow; tapping the row opens a
 * [DropdownMenu] of the options, the selected one ticked. This is the one row
 * type the settings family was missing, so "pick a value" settings were each
 * hand-rolling a menu. Its shape matches [SettingsRow]/[SettingsSwitchRow] so a
 * screen stays a uniform list of rows.
 */
@Composable
fun <T> SettingsSelectRow(
    title: String,
    selected: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            title = title,
            modifier = modifier,
            supportingText = supportingText ?: label(selected),
            enabled = enabled,
            onClick = { expanded = true },
            leadingContent = leadingContent,
            trailingContent = { IconArrowDropDown() },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                SelectableDropdownMenuItem(
                    selected = option == selected,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    text = { Text(label(option)) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}

/**
 * The dense, form-style select: a read-only [OutlinedTextField] with an
 * [ExposedDropdownMenuBox], for settings that read like a form field rather than
 * a list row (the camera codec/audio pickers). Prefer [SettingsSelectRow] for
 * ordinary settings lists.
 *
 * [itemSupporting] adds a second line under each option (e.g. a codec's
 * description).
 */
@Composable
fun <T> SettingsExposedSelectRow(
    label: String,
    selected: T,
    options: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemSupporting: ((T) -> String?)? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = itemLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(itemLabel(option))
                            val supporting = itemSupporting?.invoke(option)
                            if (supporting != null) {
                                Text(
                                    supporting,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
