package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

/**
 * The scaffold for a detail screen - one whose body is a vertically scrolling
 * stack of blocks rather than a flat list (album header + tracks, a config's
 * fields, a contact's cards).
 *
 * [ListPage] covers list screens and [AppScaffold] covers everything with a bar
 * and free-form content; this fills the gap that every detail screen was closing
 * by hand: `AppScaffold { pad -> Column(fillMaxSize().padding(pad)
 * .padding(horizontal = 16.dp).verticalScroll(...), spacedBy(12.dp)) { ... } }`.
 * The horizontal inset ([paneContentMargin], [Spacing.lg] on phones and wider on
 * expanded widths) and the gap between blocks ([Spacing.md])
 * are normalised here so detail screens stop drifting apart.
 *
 * The content lambda runs in a [ColumnScope] that is already scrolling; use
 * [DetailLazyColumn] instead when the body is itself a long list that should
 * lazily compose.
 *
 * [isRefreshing]/[onRefresh] are forwarded to [AppScaffold]: while a refresh
 * runs the scaffold's pull-to-refresh indicator shows over the detail column
 * (see AppScaffold). [search] is forwarded likewise and renders the shared
 * search field under the bar (see [ScaffoldSearch]).
 *
 * On wide windows the scrolling column is centred and capped at
 * [DesktopContentMaxWidth] so forms and detail blocks keep a readable
 * measure instead of stretching edge to edge. Pass [fullBleed] = true only
 * for a detail whose content genuinely needs the full window width (a
 * full-width hero or media surface); the lazy variant is never capped.
 */
@Composable
fun DetailScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    fullBleed: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    search: ScaffoldSearch? = null,
    content: @Composable ColumnScope.() -> Unit,
) = AppScaffold(
    title = title,
    modifier = modifier,
    onNavigateBack = onNavigateBack,
    onClose = onClose,
    navigationIcon = navigationIcon,
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    bottomBar = bottomBar,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
    search = search,
) { pad ->
    DetailColumn(pad, fillHeight = false, fullBleed = fullBleed, content)
}

/** [DetailScaffold] with a title slot, for a styled or truncated heading. */
@Composable
fun DetailScaffold(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    fillHeight: Boolean = false,
    fullBleed: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    search: ScaffoldSearch? = null,
    content: @Composable ColumnScope.() -> Unit,
) = AppScaffold(
    title = title,
    modifier = modifier,
    onNavigateBack = onNavigateBack,
    onClose = onClose,
    navigationIcon = navigationIcon,
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    bottomBar = bottomBar,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
    search = search,
) { pad ->
    DetailColumn(pad, fillHeight, fullBleed, content)
}

/** [DetailScaffold] for a screen that owns a back stack, wiring the back button to it. */
@Composable
fun <T : NavKey> DetailScaffold(
    title: String,
    backStack: NavBackStack<T>,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    fullBleed: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    search: ScaffoldSearch? = null,
    content: @Composable ColumnScope.() -> Unit,
) = DetailScaffold(
    title = title,
    modifier = modifier,
    onNavigateBack = { backStack.pop() },
    onClose = onClose,
    navigationIcon = navigationIcon,
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    bottomBar = bottomBar,
    fullBleed = fullBleed,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
    search = search,
    content = content,
)

/**
 * The detail scaffold whose body is a lazily composed list - album track lists,
 * long grouped detail screens. Same bars and insets as [DetailScaffold], but the
 * scaffold padding is forwarded as the list's `contentPadding` (with the
 * horizontal margin folded in) so items can scroll under the bars.
 */
@Composable
fun DetailLazyColumn(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    search: ScaffoldSearch? = null,
    content: LazyListScope.() -> Unit,
) = AppScaffold(
    title = title,
    modifier = modifier,
    onNavigateBack = onNavigateBack,
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    bottomBar = bottomBar,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
    search = search,
) { pad ->
    DetailList(pad, content)
}

/** [DetailLazyColumn] with a title slot. */
@Composable
fun DetailLazyColumn(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    search: ScaffoldSearch? = null,
    content: LazyListScope.() -> Unit,
) = AppScaffold(
    title = title,
    modifier = modifier,
    onNavigateBack = onNavigateBack,
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    bottomBar = bottomBar,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
    search = search,
) { pad ->
    DetailList(pad, content)
}

/** [DetailLazyColumn] for a screen that owns a back stack. */
@Composable
fun <T : NavKey> DetailLazyColumn(
    title: String,
    backStack: NavBackStack<T>,
    modifier: Modifier = Modifier,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    bottomBar: @Composable () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    search: ScaffoldSearch? = null,
    content: LazyListScope.() -> Unit,
) = DetailLazyColumn(
    title = title,
    modifier = modifier,
    onNavigateBack = { backStack.pop() },
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    bottomBar = bottomBar,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    refreshEnabled = refreshEnabled,
    search = search,
    content = content,
)

@Composable
private fun DetailColumn(
    pad: PaddingValues,
    fillHeight: Boolean,
    fullBleed: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Centre the scrolling column at a readable measure on wide windows;
    // fullBleed details keep the whole width. The cap lives here, on the
    // scrolling form column only - DetailList (lazy) content is not capped.
    val contentMaxWidth: Dp = if (fullBleed) Dp.Infinity else DesktopContentMaxWidth
    if (!fillHeight) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = paneContentMargin())
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
        return
    }
    // Constrain the scrolling column to be at least as tall as the viewport so a
    // weighted child in [content] can fill the empty space below the blocks (and
    // still scroll once the content outgrows the screen).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad),
        contentAlignment = Alignment.TopCenter,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxWidth(),
        ) {
            val minContentHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = paneContentMargin())
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minContentHeight),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

@Composable
private fun DetailList(pad: PaddingValues, content: LazyListScope.() -> Unit) {
    val direction = LocalLayoutDirection.current
    val margin = paneContentMargin()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = pad.calculateStartPadding(direction) + margin,
            end = pad.calculateEndPadding(direction) + margin,
            top = pad.calculateTopPadding(),
            bottom = pad.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

/**
 * A titled sub-block inside a [DetailScaffold] - an optional [titleSmall]
 * heading over its content. Pass a null [title] for an untitled group.
 */
@Composable
fun DetailSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        content()
    }
}
