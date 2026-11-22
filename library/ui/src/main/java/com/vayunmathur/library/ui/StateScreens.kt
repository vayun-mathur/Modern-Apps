package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.log.Log

/**
 * The three "the list isn't showing you anything" screens: nothing here,
 * loading, and it broke.
 *
 * Seventeen apps had written their own empty state, between them using
 * thirty-two different strings and no two layouts quite alike; loading was
 * usually a bare centred spinner and errors were often just a toast. These
 * give all of that one shape.
 *
 * Whether a screen has an empty state at all, and what it says, stays a per-app
 * decision - only the layout is shared.
 */

/**
 * Shown in place of content when there is legitimately nothing to display.
 *
 * [icon] and [action] are optional: a filtered list that came back empty wants
 * neither, while a first-run screen usually wants both.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.outline
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) action()
        }
    }
}

/**
 * Shown while content is being fetched or built.
 *
 * This is the canonical whole-content loading UI: a centred indeterminate
 * [LoadingIndicator] (MaterialComponents.kt) plus an optional [message].
 * Screens must render this (or a bare [LoadingIndicator] in a small slot)
 * whenever their model says data is still loading - never a blank screen
 * and never the loaded-but-empty [EmptyState].
 *
 * The spinner animates indefinitely, so under Robolectric it never lets
 * the paused looper idle: tests must await CONTENT (text, a row, a node),
 * never looper idle, while a loading state is on screen.
 *
 * [message] is worth passing whenever the wait can be long enough to wonder
 * whether anything is happening.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LoadingIndicator()
            if (message != null) {
                Text(
                    message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Shown when content could not be loaded.
 *
 * Distinct from [EmptyState] on purpose: "there is nothing" and "we could not
 * find out" are different situations and should not look the same. [onRetry]
 * is optional because not everything can be retried.
 *
 * Pass [error] when the failure came from a [Throwable]: it is logged once
 * (per distinct instance) via `Log.errorWithId` and the minted correlation
 * id is rendered small under the message, so a user report quoting that id
 * can be matched to the log line. [errorWithId] itself calls
 * `Log.newErrorId` internally, so the returned id is the logged one — do
 * not mint a separate id here. When [message] is null the throwable is
 * mapped through [friendlyError] (raw exception text never reaches the UI);
 * an explicit [message] still wins. Call sites that pass only authored
 * strings (domain messages, not failures to correlate) leave [error] null
 * and get no id.
 */
@Composable
fun ErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    error: Throwable? = null,
) {
    // Mint + log once per distinct throwable, not on every recomposition.
    val errorId = remember(error) {
        error?.let { Log.errorWithId("ErrorState", "ErrorState: $title", it) }
    }
    val displayMessage = message ?: if (error != null) friendlyError(error) else null
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            if (displayMessage != null) {
                Text(
                    displayMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
            if (errorId != null) {
                Text(
                    stringResource(R.string.error_id_format, errorId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
            if (onRetry != null && retryLabel != null) {
                Button(onClick = onRetry) { Text(retryLabel) }
            }
        }
    }
}
