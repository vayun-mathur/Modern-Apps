package com.vayunmathur.setupwizard.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.setupwizard.R

/**
 * Offers to restore apps and data from a backup taken on a previous device.
 *
 * The step is only reached when something actually handles the restore intent - see
 * [com.vayunmathur.setupwizard.platform.SetupIntents.restoreBackup] - so the button here
 * always leads somewhere.
 */
@Composable
fun MigrationScreen(onRestore: () -> Unit, onSkip: () -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.restore_apps_and_data),
        subtitle = stringResource(R.string.data_restore_desc),
        icon = { IconRestore() },
        primaryAction = SetupAction(stringResource(R.string.restore), onRestore),
        secondaryAction = SetupAction(stringResource(R.string.skip), onSkip),
    )
}
