package com.vayunmathur.setupwizard.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.vayunmathur.setupwizard.Route
import com.vayunmathur.setupwizard.platform.SetupIntents

@Composable
internal fun MigrationStep(
    context: Context,
    onAdvance: (Route) -> Unit,
    onAdvanceReplacing: (Route) -> Unit,
) {
    // Resolved once rather than on every recomposition: this is a PackageManager query.
    val restore = remember { SetupIntents.restoreBackup(context) }
    // Nothing restores backups on this image yet, so the step is not shown at all
    // rather than offering a button that opens nothing. See SetupIntents.
    if (restore == null) {
        LaunchedEffect(Unit) { onAdvanceReplacing(Route.Migration) }
    } else {
        val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
            // Backing out of the restore leaves the user here, with Skip still
            // available; anything else means a restore was started and the step is done.
            if (it.resultCode != Activity.RESULT_CANCELED) onAdvance(Route.Migration)
        }
        MigrationScreen(
            onRestore = {
                // The intent was resolved at composition time; the handler can be
                // disabled or removed before the tap, and launch then throws.
                try {
                    launcher.launch(restore)
                } catch (_: ActivityNotFoundException) {
                    // No restore handler anymore; Skip remains available.
                }
            },
            onSkip = { onAdvance(Route.Migration) },
        )
    }
}
