package com.vayunmathur.euicc.ui.download

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * The eUICC is being written to.
 *
 * No actions at all - not even cancel. Once the Bound Profile Package starts streaming
 * into the eUICC there is no safe way to stop it, and offering a button that cannot honour
 * what it promises is worse than offering none.
 *
 * [progress] is null until the native core reports segment progress, in which case the bar
 * is indeterminate.
 */
@Composable
fun InstallingContent(progress: Float?) {
    SetupScaffold(
        title = stringResource(R.string.installing_title),
        subtitle = stringResource(R.string.installing_text),
        icon = { IconSim() },
    ) {
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
