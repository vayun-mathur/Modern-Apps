package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

/**
 * Title alignment for [AppScaffold].
 *
 * A real design choice rather than an inconsistency, so it stays a per-screen
 * decision: [Start] is the workhorse for content screens, [Center] suits
 * single-purpose or top-level screens. What [AppScaffold] fixes is that
 * choosing between them used to mean writing the whole scaffold out again.
 */
enum class AppBarAlignment { Start, Center }

/**
 * How tall [AppScaffold]'s top bar is, and so how much presence its title has.
 *
 * [Small] is the default and stays the default deliberately: it is what all 39 modules using
 * [AppScaffold] already render, and the taller bars change every screen's vertical rhythm.
 * The two flexible sizes are the Expressive two-row bars - they collapse to a single row as
 * content scrolls under them, and are worth opting into on a screen whose title is the screen's
 * identity rather than a label above content. [LargeFlexible] treats the title as the headline;
 * [MediumFlexible] is the middle ground.
 *
 * A flexible size only collapses because [AppScaffold] feeds its `scrollBehavior` nested-scroll
 * deltas; pair one with [appBarScrollBehavior] and the bar behaves.
 */
enum class AppBarSize { Small, MediumFlexible, LargeFlexible }

/**
 * Scaffold for an ordinary screen: a top app bar with an optional back button,
 * optional actions, and content.
 *
 * Fifty-five files had written out the same `Scaffold` + `TopAppBar` +
 * `IconNavigation(backStack)` by hand. [ListPage] already covers list screens;
 * this is for everything else.
 *
 * The content lambda receives the scaffold's [PaddingValues] and must apply
 * them - it is not applied here so that a screen can let a list scroll under
 * the bars while still insetting its own items.
 *
 * [scrollBehavior] is required rather than defaulted so that every screen states how its bar
 * reacts to scrolling; [appBarScrollBehavior] gives the right one for a [size].
 */
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) = AppScaffold(
    title = { Text(title) },
    modifier = modifier,
    subtitle = subtitle?.let { text -> { Text(text) } },
    onNavigateBack = onNavigateBack,
    onClose = onClose,
    navigationIcon = navigationIcon,
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    floatingActionButton = floatingActionButton,
    bottomBar = bottomBar,
    snackbarHost = snackbarHost,
    content = content,
)

/**
 * [AppScaffold] taking a title slot rather than a string.
 *
 * For the handful of screens whose title needs more than text - a truncated
 * document name, a styled or two-line heading. Prefer the string overload.
 */
@Composable
fun AppScaffold(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val resolvedNavigationIcon = resolveNavigationIcon(navigationIcon, onClose, onNavigateBack)
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppTopBar(
                title = title,
                subtitle = subtitle,
                navigationIcon = resolvedNavigationIcon,
                actions = actions,
                alignment = alignment,
                size = size,
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/**
 * [AppScaffold] whose top bar is supplied by the screen (e.g. a search field), or
 * omitted entirely with `topBar = {}`. Without a bar the content padding still
 * covers the status bar.
 */
@Composable
fun AppScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) = Scaffold(
    modifier = modifier,
    topBar = topBar,
    floatingActionButton = floatingActionButton,
    bottomBar = bottomBar,
    snackbarHost = snackbarHost,
    content = content,
)

/** [AppScaffold] for a screen that owns a back stack, wiring the back button to it. */
@Composable
fun <T : NavKey> AppScaffold(
    title: String,
    backStack: NavBackStack<T>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    size: AppBarSize = AppBarSize.Small,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) = AppScaffold(
    title = title,
    modifier = modifier,
    subtitle = subtitle,
    onNavigateBack = { backStack.pop() },
    onClose = onClose,
    navigationIcon = navigationIcon,
    alignment = alignment,
    size = size,
    actions = actions,
    scrollBehavior = scrollBehavior,
    floatingActionButton = floatingActionButton,
    bottomBar = bottomBar,
    snackbarHost = snackbarHost,
    content = content,
)

/**
 * The top bar [AppScaffold] renders, resolved from [size] and [alignment].
 *
 * Kept private and shared so the three overloads cannot drift apart. [subtitle] is only rendered
 * by the flexible sizes - the small bar is a single row and has nowhere to put it.
 */
@Composable
private fun AppTopBar(
    title: @Composable () -> Unit,
    subtitle: (@Composable () -> Unit)?,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    alignment: AppBarAlignment,
    size: AppBarSize,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val titleAlignment = when (alignment) {
        AppBarAlignment.Start -> Alignment.Start
        AppBarAlignment.Center -> Alignment.CenterHorizontally
    }
    when (size) {
        AppBarSize.Small -> when (alignment) {
            AppBarAlignment.Start -> TopAppBar(
                title = title, navigationIcon = navigationIcon,
                actions = actions, scrollBehavior = scrollBehavior,
            )
            AppBarAlignment.Center -> CenterAlignedTopAppBar(
                title = title, navigationIcon = navigationIcon,
                actions = actions, scrollBehavior = scrollBehavior,
            )
        }
        AppBarSize.MediumFlexible -> MediumFlexibleTopAppBar(
            title = title, subtitle = subtitle, navigationIcon = navigationIcon,
            actions = actions, titleHorizontalAlignment = titleAlignment,
            scrollBehavior = scrollBehavior,
        )
        AppBarSize.LargeFlexible -> LargeFlexibleTopAppBar(
            title = title, subtitle = subtitle, navigationIcon = navigationIcon,
            actions = actions, titleHorizontalAlignment = titleAlignment,
            scrollBehavior = scrollBehavior,
        )
    }
}

/**
 * Resolves the top-bar navigation icon from the available options, in priority
 * order: an explicit [navigationIcon] slot wins; otherwise [onClose] renders a
 * Close (X) button; otherwise [onNavigateBack] renders the default back arrow;
 * otherwise nothing is shown. Kept as a single source of truth so every
 * [AppScaffold] overload behaves identically.
 */
private fun resolveNavigationIcon(
    navigationIcon: (@Composable () -> Unit)?,
    onClose: (() -> Unit)?,
    onNavigateBack: (() -> Unit)?,
): @Composable () -> Unit = navigationIcon ?: {
    when {
        onClose != null -> IconButton(onClick = onClose) { IconClose() }
        onNavigateBack != null -> IconNavigation(onNavigateBack)
    }
}
