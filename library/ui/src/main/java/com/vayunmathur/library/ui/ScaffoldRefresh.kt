@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * The scaffold-owned refresh surface: a Material 3 pull-to-refresh box around
 * scaffold content.
 *
 * While [isRefreshing] is true — whether the refresh was started by a pull
 * gesture or programmatically by the model — the pull-to-refresh indicator
 * shows at the top of the content area and the content stays composed under
 * it. This is pull-to-refresh semantics, not content replacement: a screen
 * that is refreshing keeps its rows on screen, and a first load simply shows
 * the indicator over whatever (usually empty) content area the screen
 * composes. Call sites keep first-load empty states from flashing by gating
 * their empty branch on a `hasLoaded` flag, not by asking the scaffold to
 * hide the content.
 *
 * The gesture is only wired when [refreshEnabled] is true and [onRefresh] is
 * non-null; in every other case the box still reflects a programmatic
 * [isRefreshing] with the same top indicator, it just cannot be pulled.
 *
 * [padding] is the scaffold's content padding: it is forwarded to [content]
 * untouched (screens keep owning how they apply it) and only its top inset
 * is used here, to drop the indicator below the top bar.
 */
@Composable
internal fun ScaffoldRefreshBox(
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?,
    refreshEnabled: Boolean,
    padding: PaddingValues,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (refreshEnabled && onRefresh != null) {
        val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            state = state,
            indicator = {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = padding.calculateTopPadding()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                        state = state,
                        isRefreshing = isRefreshing,
                    )
                }
            },
        ) {
            content(padding)
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            content(padding)
            if (isRefreshing) {
                val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = padding.calculateTopPadding()),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                        state = state,
                        isRefreshing = true,
                    )
                }
            }
        }
    }
}
