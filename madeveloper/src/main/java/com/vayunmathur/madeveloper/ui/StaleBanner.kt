package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.madeveloper.platform.MaDevUiState
import java.text.DateFormat
import java.util.Date

/**
 * Stale-data indication: the screen is showing last-good data because the
 * live fetch failed. Rendered only when [MaDevUiState.stale] is set, so a
 * live screen looks exactly as before.
 */
@Composable
fun StaleBanner(state: MaDevUiState, modifier: Modifier = Modifier) {
    if (!state.stale) return
    val at = state.staleAtMillis?.let {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconWarning(tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            if (at != null) "Showing saved data from $at — couldn't reach the server"
            else "Showing saved data — couldn't reach the server",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
