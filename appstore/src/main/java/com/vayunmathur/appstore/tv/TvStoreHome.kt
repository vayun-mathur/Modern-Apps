package com.vayunmathur.appstore.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvChip
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconPackage

/**
 * The store front on TV: the phone's home reflowed for a wide screen. One
 * scrolling grid: the updates banner, the category chips, then one headed block
 * per source — same sections, same order, same titles and subtitles as the phone.
 *
 * Two grid rows per section (six cards): the screen shows a dozen+ items without
 * scrolling, and Browse lists the whole catalogue behind each header.
 */
@Composable
fun TvStoreHome(
    viewModel: TvStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenBrowse: (TvSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.home.collectAsState()
    // The updates banner counts every source, but the Play half of the update check
    // only ran when the Updates tab was opened — a fresh launch showed the F-Droid
    // count until the user visited that tab (issue #701). Kick off one check from
    // home. Mirrors the phone's HomePage.
    LaunchedEffect(Unit) {
        viewModel.checkForUpdates()
    }
    TvStoreHomeScreen(
        state = state,
        onSelectCategory = viewModel::selectCategory,
        onAppClick = onAppClick,
        onOpenUpdates = onOpenUpdates,
        onOpenBrowse = onOpenBrowse,
        modifier = modifier,
    )
}

@Composable
internal fun TvStoreHomeScreen(
    state: TvHomeUiState,
    modifier: Modifier = Modifier,
    onSelectCategory: (String?) -> Unit = {},
    onAppClick: (UnifiedApp) -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenBrowse: (TvSection) -> Unit = {},
) {
    val primaryFocus = remember { FocusRequester() }
    // Unkeyed: runs once. Keying on the update count would yank focus back to the
    // banner every time a check completes or an update installs and drops off.
    TvInitialFocus(primaryFocus)
    when {
        state.isLoading && state.sections.isEmpty() ->
            TvLoading(label = stringResource(R.string.sync_in_progress))
        state.sections.isEmpty() -> TvEmptyState(
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_message),
            icon = { IconPackage() },
        )
        else -> TvGrid(columns = 3, modifier = modifier.fillMaxSize()) {
            if (state.updateCount > 0) {
                tvFullWidth(key = "updates") {
                    TvUpdatesBanner(
                        count = state.updateCount,
                        onClick = onOpenUpdates,
                        focusRequester = primaryFocus,
                    )
                }
            }
            if (state.categories.isNotEmpty()) {
                tvFullWidth(key = "categories") {
                    TvCategoryChips(
                        categories = state.categories,
                        selected = state.selectedCategory,
                        onSelect = onSelectCategory,
                    )
                }
            }
            // Transient sync line, e.g. what the catalogues are currently fetching.
            if (state.statusMessage.isNotBlank()) {
                tvFullWidth(key = "status") {
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            tvHomeSections(state = state, onAppClick = onAppClick, onOpenBrowse = onOpenBrowse)
        }
    }
}

/**
 * The phone's "N updates available · review and install" banner as one full-width
 * focusable row: the count, the phone's subtitle, and a chevron.
 */
@Composable
private fun TvUpdatesBanner(
    count: Int,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    TvButton(
        text = pluralStringResource(R.plurals.updates_count, count, count) +
            " · " + stringResource(R.string.updates_banner_subtitle),
        onClick = onClick,
        primary = true,
        icon = { IconDownload() },
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

/** The phone's category chips as a wrapping row inside one full-width grid item. */
@Composable
private fun TvCategoryChips(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // FlowRow would want the experimental layout API; a fixed 6-per-row chunk
    // reads the same on a TV and keeps the imports stable. The All chip leads.
    val cells = listOf<String?>(null) + categories.take(CATEGORY_CHIP_LIMIT)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(CATEGORY_CHIPS_PER_ROW).forEach { chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEach { category ->
                    if (category == null) {
                        TvChip(
                            label = stringResource(R.string.filter_all),
                            selected = selected == null,
                            onClick = { onSelect(null) },
                        )
                    } else {
                        TvChip(
                            label = category,
                            selected = selected == category,
                            onClick = { onSelect(if (selected == category) null else category) },
                        )
                    }
                }
            }
        }
    }
}

private const val HOME_CARDS_PER_SECTION = 6
private const val CATEGORY_CHIPS_PER_ROW = 6
private const val CATEGORY_CHIP_LIMIT = 23

/** One home section header plus its card row inside the home grid. */
private fun LazyGridScope.tvHomeSections(
    state: TvHomeUiState,
    onAppClick: (UnifiedApp) -> Unit,
    onOpenBrowse: (TvSection) -> Unit,
) {
    state.sections.forEachIndexed { index, section ->
        tvGridHeader(
            key = "${section.id}-header",
            title = section.title,
            subtitle = section.subtitle,
            first = index == 0 && state.updateCount == 0 &&
                state.categories.isEmpty() && state.statusMessage.isBlank(),
            action = {
                TvButton(
                    text = stringResource(R.string.action_browse),
                    onClick = { onOpenBrowse(section) },
                )
            },
        )
        val cards = section.apps.take(HOME_CARDS_PER_SECTION)
        items(cards, key = { "${section.id}-${it.packageName}" }) { app ->
            TvStoreAppCard(
                app = app,
                onClick = { onAppClick(app) },
                installedIcon = state.installedIcons[app.packageName],
                isInstalled = app.packageName in state.installedPackages,
                hasUpdate = app.packageName in state.updatePackages,
                stage = state.stages[app.packageName],
            )
        }
    }
}
