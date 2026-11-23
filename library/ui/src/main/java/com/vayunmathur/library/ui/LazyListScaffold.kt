@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A scaffold whose body is a single lazily-composed list.
 *
 * Collapses the `Scaffold { pad -> LazyColumn(contentPadding = ...) { ... } }`
 * combo every list screen was writing by hand, and owns the one detail those
 * copies kept getting wrong: the [LazyColumn]'s `contentPadding` is the
 * scaffold's own [PaddingValues] on *all four sides* (mirroring [DetailScaffold]'s
 * `DetailList`), so items scroll clear of the bars and nothing double-insets or
 * drops the bottom inset. Only a symmetric [horizontalPadding] is folded onto the
 * forwarded start/end; the top and bottom are always exactly the scaffold's.
 *
 * The caller supplies the chrome the list needs: an add-style [floatingActionButton]
 * and a swappable [topBar] - e.g. the normal-vs-selection top bar a multi-select
 * screen toggles between (see `SelectionMode.kt` for that pattern). For the common
 * case a plain bar is enough, pass [title] and/or [actions] and the scaffold builds
 * the [TopAppBar] for you; with neither, and no [topBar], no bar is drawn. Everything
 * else matches the shared [Scaffold] wrapper (same param names/types) so it stays a
 * drop-in replacement.
 *
 * [expandedWidthPadding] is extra horizontal padding applied on top of
 * [horizontalPadding] only on expanded widths - the two-pane tablet/foldable
 * case - so list panes gain the wider gutter there while phone layouts keep
 * exactly the [horizontalPadding] they pass today. It defaults to the adaptive
 * [paneContentMargin], which is [Spacing.lg] on compact widths; callers that
 * already pass their own margin (e.g. 16.dp) can pass [Spacing.lg] explicitly
 * to stay fixed, or `0.dp` to opt out entirely.
 *
 * [isRefreshing]/[onRefresh] drive the scaffold's pull-to-refresh indicator
 * over the list (the scaffold's bars stay); see [AppScaffold].
 *
 * [overlay] is drawn on top of the list in a [Box] exactly the list's size,
 * for chrome that must track the viewport itself - e.g. a fast-scroller whose
 * travel should run along the list, not the whole screen.
 */
@Composable
fun LazyListScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    title: String = "",
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: (@Composable () -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    state: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = 0.dp,
    expandedWidthPadding: Dp = paneContentMargin(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    containerColor: Color = MaterialTheme.colorScheme.background,
    overlay: @Composable BoxScope.() -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val behavior = scrollBehavior ?: appBarScrollBehavior()
    Scaffold(
    modifier.nestedScroll(behavior.nestedScrollConnection),
    // A caller can either pass a fully custom [topBar], or - the common case -
    // let the scaffold build a plain top bar from [title]/[actions]. When neither
    // a title nor actions are given, no bar is drawn (the historical default).
    if (actions != null || title.isNotEmpty()) {
        { TopAppBar(title = { Text(title) }, actions = actions ?: {}, scrollBehavior = behavior) }
    } else topBar,
    bottomBar,
    snackbarHost,
    floatingActionButton,
    floatingActionButtonPosition,
    containerColor,
) { pad ->
  ScaffoldRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, refreshEnabled = refreshEnabled, padding = pad) { refreshPad ->
    val dir = LocalLayoutDirection.current
    // Only the width class above compact adds padding; on phones this is
    // exactly [horizontalPadding], unchanged from before.
    val extra = (expandedWidthPadding - Spacing.lg).coerceAtLeast(0.dp)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(
                start = pad.calculateStartPadding(dir) + horizontalPadding + extra,
                end = pad.calculateEndPadding(dir) + horizontalPadding + extra,
                top = pad.calculateTopPadding(),
                bottom = pad.calculateBottomPadding(),
            ),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        overlay()
    }
  }
}
}
