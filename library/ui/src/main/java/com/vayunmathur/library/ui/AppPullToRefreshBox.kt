@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Library-internal pull-to-refresh container. App screens must not use
 * this directly: the AppScaffold family owns the pull-to-refresh box via
 * `isRefreshing`/`onRefresh`/`refreshEnabled` (flagged by ScaffoldLoading
 * lint in app code). Kept for library-internal hosts without a scaffold.
 *
 * A thin fix over [PullToRefreshBox] for screens hosted in [AppScaffold] (or a raw
 * `Scaffold` with a top bar): the indicator slot is a `fillMaxWidth` box with centered
 * content rather than a wrap-content `Box.align`, so the spinner is horizontally centered
 * regardless of how the content measures (grids, pinch-zoom layers), and [topClearance]
 * drops it below the top bar / floating search bar the M3 default top-center slot would
 * otherwise sit under.
 *
 * The caller owns content insets (the scaffold's `PaddingValues` go on the content, not
 * on [modifier]) while this box owns only the indicator offset, so the two cannot
 * double-offset. Pass a larger [topClearance] when extra in-content rows (filter chips,
 * action rows) sit above the refreshable area.
 *
 * @param topClearance distance from the top of [modifier] to the indicator. Defaults to
 * the standard small top-bar height; weather's floating search bar uses the same value.
 */
@Composable
fun AppPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    topClearance: Dp = 64.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = topClearance),
                contentAlignment = Alignment.TopCenter,
            ) {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                    state = state,
                    isRefreshing = isRefreshing,
                )
            }
        },
        content = content,
    )
}
