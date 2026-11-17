package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import com.vayunmathur.appstore.platform.AppSection
import com.vayunmathur.appstore.platform.HomeActions
import com.vayunmathur.appstore.platform.HomeUiState
import com.vayunmathur.appstore.platform.SectionLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text

/**
 * The store front: a few curated rows rather than one undifferentiated list.
 *
 * The rows come from three places and are deliberately labelled as such — this repo's own
 * apps, Play's editorial clusters, and F-Droid's newest reproduced builds. Which source a
 * row comes from is information, not a verdict; see
 * [com.vayunmathur.appstore.data.security.TrustProfile].
 *
 * Stateless so it can be rendered from a `@Preview` — see `src/screenshotTest`, which is
 * where the store listing images come from.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    onAppClick: (UnifiedApp) -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenBrowse: (AppSection) -> Unit = {},
) {
    AppScaffold(topBar = {}) { padding ->
        AppPullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = { actions.refresh() },
            modifier = Modifier.fillMaxSize(),
            topClearance = padding.calculateTopPadding(),
        ) {
            // A package can legitimately sit in several sections at once - a new release is also a
            // top download - but one detail page cannot morph from two origins. The first section
            // listing a package owns the morph; later copies of it open without one.
            val morphOwner = remember(state.sections) {
                buildMap<String, String> {
                    state.sections.forEach { section ->
                        section.apps.forEach { putIfAbsent(it.packageName, section.id) }
                    }
                }
            }
            fun sharedKeyFor(section: AppSection, app: UnifiedApp): Any? =
                if (morphOwner[app.packageName] == section.id) {
                    "appstore-app-${app.packageName}"
                } else {
                    null
                }

            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.isSyncing || state.statusMessage.isNotBlank()) {
                    item("status") { StatusLine(state.statusMessage, state.isSyncing) }
                }

                if (state.updateCount > 0) {
                    item("updates") { UpdatesBanner(state.updateCount, onOpenUpdates) }
                }

                if (state.categories.isNotEmpty()) {
                    item("categories") {
                        CategoryRow(
                            categories = state.categories,
                            selected = state.selectedCategory,
                            onSelect = actions::selectCategory,
                        )
                    }
                }

                if (state.sections.isEmpty()) {
                    item("empty") {
                        if (state.isLoading) {
                            LoadingRow()
                        } else {
                            EmptyState(
                                title = stringResource(R.string.home_empty_title),
                                message = stringResource(R.string.home_empty_message),
                                icon = { IconPackage() },
                                modifier = Modifier.height(320.dp),
                            )
                        }
                    }
                }

                state.sections.forEach { section ->
                    item("${section.id}-header") {
                        SectionHeader(
                            title = section.title,
                            subtitle = section.subtitle,
                            actionLabel = stringResource(R.string.action_browse),
                            onAction = { onOpenBrowse(section) },
                        )
                    }
                    if (section.layout == SectionLayout.CAROUSEL) {
                        item("${section.id}-body") {
                            AppCarousel(
                                apps = section.apps,
                                installedPackages = state.installedPackages,
                                installedIcons = state.installedIcons,
                                onAppClick = onAppClick,
                                stages = state.stages,
                                sharedKey = { sharedKeyFor(section, it) },
                            )
                        }
                    } else {
                        items(
                            section.apps,
                            key = { "${section.id}-${it.packageName}" },
                        ) { app ->
                            AppRow(
                                app = app,
                                isInstalled = app.packageName in state.installedPackages,
                                stage = state.stages[app.packageName],
                                installedIcon = state.installedIcons[app.packageName],
                                onClick = { onAppClick(app) },
                                sharedKey = sharedKeyFor(section, app),
                            )
                        }
                    }
                }
            }
        }
    }
}
