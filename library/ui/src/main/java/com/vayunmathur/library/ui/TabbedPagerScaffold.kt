package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.BottomNavBarItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlinx.coroutines.launch

/** One tab in a [TabbedPagerScaffold]: a label, an optional icon, and its page. */
data class PagerTab(
    val label: String,
    val icon: (@Composable () -> Unit)? = null,
    val content: @Composable () -> Unit,
)

/**
 * One tab in the route-backed [TabbedPagerScaffold]: a label, an optional icon,
 * and the [route] it selects. Unlike [PagerTab] it carries no content — the
 * destination's content is the caller's entry for [route] (see the route-backed
 * overload below).
 */
data class RouteTab<T : NavKey>(
    val route: T,
    val label: String,
    val icon: (@Composable () -> Unit)? = null,
)

/** How [TabbedPagerScaffold] presents its tabs. */
enum class TabStyle {
    /** A [BottomNavBar] at the bottom - the app-level "main tabs" case (music). */
    BottomNav,

    /** A [PrimaryTabRow] under the top bar - the in-screen tabs case. */
    PrimaryTabRow,
}

/**
 * A tab host that swipes: a set of [tabs] shown in a [HorizontalPager], kept in
 * sync with either a bottom navigation bar or a top tab row.
 *
 * Music and calendar had written out the same plumbing - a
 * [rememberCoroutineScope] used only to `animateScrollToPage`, the pager, and
 * the fiddly business of forwarding just the bottom inset so an inner page's own
 * top bar is not pushed down. That is all this owns; app-specific pagers (a
 * date-anchored calendar, say) stay hand-written.
 *
 * [leadingBottomBar] sits above the tab bar in [TabStyle.BottomNav] (music's
 * now-playing controls) and is the plain bottom bar in [TabStyle.PrimaryTabRow].
 *
 * In [TabStyle.BottomNav] the tab bar itself is width-aware: on expanded
 * windows ([WindowWidthClass.Expanded] — large tablets, ChromeOS, desktop)
 * tabs move into a [NavigationRail] on the left and only [leadingBottomBar]
 * stays at the bottom, so tabbed apps get desktop chrome without touching
 * their own code. Compact and medium windows keep the bottom bar.
 *
 * **In-screen tabs only.** This pager overload keeps the selected page in
 * pager state, so it must only host tabs that live *inside* one screen
 * ([TabStyle.PrimaryTabRow], or a [TabStyle.BottomNav] grouping that is
 * genuinely one destination's sub-state). App-level bottom-nav tabs — the
 * major screens of an app — are navigation destinations, not pages: use the
 * route-backed overload below, where each tab is a route on a
 * [NavBackStack] and switching tabs resets the stack (SiblingPage
 * semantics), so back, deep links, and process restore see the real screen.
 */
@Composable
fun TabbedPagerScaffold(
    tabs: List<PagerTab>,
    modifier: Modifier = Modifier,
    pagerState: PagerState = rememberPagerState(pageCount = { tabs.size }),
    tabStyle: TabStyle = TabStyle.BottomNav,
    topBar: @Composable () -> Unit = {},
    leadingBottomBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val select: (Int) -> Unit = { index ->
        if (pagerState.currentPage != index) scope.launch { pagerState.animateScrollToPage(index) }
    }

    when (tabStyle) {
        TabStyle.BottomNav -> {
            if (isExpandedWidth()) {
                Scaffold(
                    modifier = modifier,
                    topBar = topBar,
                    bottomBar = leadingBottomBar,
                ) { padding ->
                    // Rail owns the start inset; the pager consumes the rest. The
                    // forwarded space is consumed for the same reason as below:
                    // the rail already covers the navigation bar, so a page's own
                    // scaffold would otherwise inset for it again.
                    Row(Modifier.padding(padding)) {
                        NavigationRail(Modifier.fillMaxHeight()) {
                            tabs.forEachIndexed { index, tab ->
                                NavigationRailItem(
                                    selected = pagerState.currentPage == index,
                                    onClick = { select(index) },
                                    icon = tab.icon ?: {},
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                        val start = padding.calculateStartPadding(LocalLayoutDirection.current)
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(PaddingValues(start = start)),
                        ) { page -> tabs[page].content() }
                    }
                }
            } else {
                Scaffold(
                    modifier = modifier,
                    topBar = topBar,
                    bottomBar = {
                        Column(Modifier.fillMaxWidth()) {
                            leadingBottomBar()
                            BottomNavBar {
                                tabs.forEachIndexed { index, tab ->
                                    BottomNavBarItem(
                                        selected = pagerState.currentPage == index,
                                        onClick = { select(index) },
                                        icon = tab.icon ?: {},
                                        label = tab.label,
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    // Inner pages own their top bar and consume the top inset, so only
                    // the bottom space this scaffold's bars take is forwarded - adding
                    // the full padding here would inset the status bar twice. The
                    // forwarded space has to be consumed too: the bars already cover
                    // the navigation bar, so a page's own scaffold would otherwise
                    // inset for it again and leave a gap above the tab bar.
                    val bottom = padding.calculateBottomPadding()
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .padding(bottom = bottom)
                            .consumeWindowInsets(PaddingValues(bottom = bottom)),
                    ) { page -> tabs[page].content() }
                }
            }
        }

        TabStyle.PrimaryTabRow -> Scaffold(
            modifier = modifier,
            topBar = {
                Column(Modifier.fillMaxWidth()) {
                    topBar()
                    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { select(index) },
                                text = { Text(tab.label) },
                                icon = tab.icon,
                            )
                        }
                    }
                }
            },
            bottomBar = leadingBottomBar,
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(padding),
            ) { page -> tabs[page].content() }
        }
    }
}

/**
 * Route-backed tab host for app-level bottom-nav tabs: the same chrome as the
 * [TabStyle.BottomNav] pager overload (a [BottomNavBar] on compact/medium
 * windows, a [NavigationRail] on expanded ones, with [leadingBottomBar] above
 * the bottom bar), but driven by a [NavBackStack] instead of a pager.
 *
 * Selecting a tab calls `backStack.reset(tab.route)` — SiblingPage semantics:
 * tabs are siblings, so switching replaces the stack rather than pushing, and
 * re-selecting the current tab is a no-op. Register each tab destination with
 * `SiblingPage()` metadata in `MainNavigation` so tab switches crossfade
 * instead of sliding like a push.
 *
 * There is deliberately no pager and no swipe: [content] is invoked with the
 * route on top of [backStack] and renders that destination. It composes with
 * `MainNavigation` entries either directly —
 * `content = { route -> when (route) { ... } }` — or by hosting the
 * `MainNavigation` for [backStack] inside the content slot (the calculator
 * rail pattern), in which case the routes registered for non-tab
 * destinations pushed over a tab simply render here with no tab selected.
 *
 * The parameter list mirrors the pager overload so migrations are mechanical:
 * a `PagerTab(label, icon) { Page() }` becomes a
 * `RouteTab(route, label, icon)` plus the page moving into [content] (or into
 * a `MainNavigation` entry for that route).
 */
@Composable
fun <T : NavKey> TabbedPagerScaffold(
    backStack: NavBackStack<T>,
    tabs: List<RouteTab<T>>,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    leadingBottomBar: @Composable () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    val current = backStack.last()
    val select: (RouteTab<T>) -> Unit = { tab ->
        if (current != tab.route) backStack.reset(tab.route)
    }

    if (isExpandedWidth()) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = leadingBottomBar,
        ) { padding ->
            // Rail owns the start inset; content consumes the rest (same
            // forwarding as the pager overload's expanded branch).
            Row(Modifier.padding(padding)) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = tab.route == current,
                            onClick = { select(tab) },
                            icon = tab.icon ?: {},
                            label = { Text(tab.label) },
                        )
                    }
                }
                val start = padding.calculateStartPadding(LocalLayoutDirection.current)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .consumeWindowInsets(PaddingValues(start = start)),
                ) { content(current) }
            }
        }
    } else {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = {
                Column(Modifier.fillMaxWidth()) {
                    leadingBottomBar()
                    BottomNavBar {
                        tabs.forEach { tab ->
                            BottomNavBarItem(
                                selected = tab.route == current,
                                onClick = { select(tab) },
                                icon = tab.icon ?: {},
                                label = tab.label,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // Only the bottom inset is forwarded, as in the pager overload:
            // inner pages own their top bar and consume the top inset.
            val bottom = padding.calculateBottomPadding()
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = bottom)
                    .consumeWindowInsets(PaddingValues(bottom = bottom)),
            ) { content(current) }
        }
    }
}

/**
 * A lightweight, non-swiping tab row - the "product tabs" look (travel). For swipeable page
 * hosting use [TabbedPagerScaffold].
 *
 * A [ButtonGroup] rather than a row of chips: the choices are one control, so they should read
 * as one object. The group also owns its own overflow menu, which is why this no longer needs
 * to scroll horizontally - a label that does not fit becomes a menu entry instead of scrolling
 * off the edge where it is easy to miss.
 */
@Composable
fun ChipTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonGroup(modifier = modifier.padding(horizontal = Spacing.lg)) {
        tabs.forEachIndexed { index, label ->
            toggleableItem(
                checked = selected == index,
                label = label,
                onCheckedChange = { onSelect(index) },
            )
        }
    }
}
