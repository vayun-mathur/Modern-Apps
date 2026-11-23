package com.vayunmathur.library.ui

import androidx.compose.ui.Modifier

/**
 * Screen-level search declared through the `AppScaffold` family.
 *
 * Pass a non-null [ScaffoldSearch] as a scaffold's `search` parameter and the
 * scaffold renders the shared inline filter field (see [CommonSearchBar]) AS
 * the top app bar: the field replaces the title in the bar's title slot (the
 * ListPage / notes / contacts placement), with the bar's navigation icon and
 * actions still rendering around it. Pass null (the default) for no search —
 * the title renders normally. The field is always visible while
 * [ScaffoldSearch] is supplied: screens that reveal search from a toolbar
 * icon keep that toggle as their own state and pass `search` only while it
 * is on (the email list does this), so their title returns when search ends.
 *
 * Search is deliberately NOT offered by every scaffold variant:
 *  - the `AppScaffold(topBar = ...)` overload owns no bar of its own, so it
 *    has nowhere sanctioned to put the field — use a titled overload;
 *  - [SetupScaffold]'s heading is its content and a setup step has nothing
 *    to filter;
 *  - [TabbedPagerScaffold] hosts pages that own their own scaffolds, so
 *    search belongs to the page's scaffold, not the tab host;
 *  - TV screens use the TV family's own text field, not this one.
 *
 * [ListPage] predates this type and keeps its own internal `searchEnabled`
 * state in the same title area; it is already scaffold-hosted and unchanged.
 *
 * [onSearch] is the IME search action. Live filtering has normally already
 * applied the query by then, so the default just dismisses the keyboard;
 * explicit-submit screens (shop) pass one to run their search.
 *
 * [modifier] is forwarded to the field, e.g. for a `focusRequester` on a
 * dedicated search screen (appstore, shop).
 */
data class ScaffoldSearch(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val placeholder: String = "Search",
    val modifier: Modifier = Modifier,
    val onSearch: ((String) -> Unit)? = null,
)
