package com.vayunmathur.everysync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.everysync.R
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text

/**
 * Sync-health banner: shown when the last sync aborted instead of merging.
 * The text is pre-mapped by `EverySyncViewModel.syncErrorText` (stable abort
 * codes to user-facing strings) — this composable never sees raw errors, key
 * material, or record contents.
 */
@Composable
fun SyncErrorBanner(errorText: String) {
    ListItem(
        headlineContent = { Text(errorText) },
        supportingContent = { Text(stringResource(R.string.sync_error_hint)) },
    )
}

