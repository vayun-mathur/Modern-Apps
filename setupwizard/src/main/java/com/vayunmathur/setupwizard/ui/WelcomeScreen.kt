package com.vayunmathur.setupwizard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconAccessibility
import com.vayunmathur.library.ui.IconLanguage
import com.vayunmathur.library.ui.IconShield
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.ui.dialogs.LanguagePickerDialog
import java.util.Locale

/**
 * The first step: what this device is, what language it speaks, and - if the bootloader is
 * unlocked - that none of the rest of it can be trusted.
 *
 * [onEmergencyCall] is null on a device that cannot place calls, which hides the button rather
 * than showing one that opens nothing.
 */
@Composable
fun WelcomeScreen(
    state: SetupUiState,
    languages: () -> List<Locale>,
    onLanguageSelected: (Locale) -> Unit,
    onAccessibility: () -> Unit,
    onEmergencyCall: (() -> Unit)?,
    onNext: () -> Unit,
) {
    var pickingLanguage by remember { mutableStateOf(false) }

    SetupScaffold(
        title = stringResource(R.string.welcome_to_modern_apps_os),
        subtitle = stringResource(R.string.modern_apps_os_desc),
        icon = { IconShield() },
        primaryAction = SetupAction(stringResource(R.string.next), onNext),
        secondaryAction = onEmergencyCall?.let {
            SetupAction(stringResource(R.string.emergency_call), it)
        },
    ) {
        if (state.bootloaderUnlocked) UnlockedBootloaderNotice()

        Text(
            stringResource(
                if (state.isPrimaryUser) R.string.lets_setup_your_device
                else R.string.lets_setup_your_profile
            ),
            style = MaterialTheme.typography.bodyLarge,
        )

        EntryRow(
            label = state.language.ifBlank { stringResource(R.string.english_united_states) },
            icon = { IconLanguage() },
            onClick = { pickingLanguage = true },
        )
        EntryRow(
            label = stringResource(R.string.accessibility),
            icon = { IconAccessibility() },
            onClick = onAccessibility,
        )
    }

    if (pickingLanguage) {
        LanguagePickerDialog(
            languages = languages,
            onSelected = {
                pickingLanguage = false
                onLanguageSelected(it)
            },
            onDismiss = { pickingLanguage = false },
        )
    }
}

/**
 * Shown above everything else when the bootloader is unlocked, in the error colours, because
 * every promise the rest of this screen makes is void while it is.
 *
 * The URL is plain text on purpose: there is no browser to open during setup, so it is
 * something to read off onto another device.
 */
@Composable
private fun UnlockedBootloaderNotice() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            IconWarning(tint = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.oem_unlocked_warning),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.visit_this_link_on_another_device),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.oem_lock_url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ColumnScope.EntryRow(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
