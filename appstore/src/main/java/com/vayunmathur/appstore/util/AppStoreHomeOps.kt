package com.vayunmathur.appstore.util

import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.CAROUSEL_LIMIT
import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.PLAY_CLUSTER_LIMIT
import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.RECENT_PER_SOURCE_LIMIT
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

// ---- Home ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

/**
 * Fill the home screen.
 *
 * The offline rows come straight from Room and are already on screen by the time this
 * runs; what it adds is Play's editorial clusters and top chart, which need an
 * anonymous account. Those failing is normal — no network, no account — and leaves the
 * offline rows exactly as they were rather than emptying the screen.
 */
internal suspend fun AppStoreViewModel.loadHome(enabled: Set<AppSource> = enabledSources.value) {
    isLoadingHomeFlow.value = true
    val modernEnabled = AppSource.MODERN_APPS in enabled
    val fdroidEnabled = AppSource.FDROID in enabled
    val izzyEnabled = AppSource.IZZY in enabled
    val proprietaryEnabled = AppSource.PROPRIETARY in enabled
    if (modernEnabled || fdroidEnabled || izzyEnabled || proprietaryEnabled) {
        modernRecentFlow.value = if (modernEnabled) {
            catalog.recentlyUpdatedBySource(AppSource.MODERN_APPS, RECENT_PER_SOURCE_LIMIT)
        } else {
            emptyList()
        }
        fdroidRecentFlow.value = if (fdroidEnabled) {
            catalog.recentlyUpdatedBySource(AppSource.FDROID, RECENT_PER_SOURCE_LIMIT)
        } else {
            emptyList()
        }
        izzyRecentFlow.value = if (izzyEnabled) {
            catalog.recentlyUpdatedBySource(AppSource.IZZY, RECENT_PER_SOURCE_LIMIT)
        } else {
            emptyList()
        }
        proprietaryRecentFlow.value = if (proprietaryEnabled) {
            catalog.recentlyUpdatedBySource(AppSource.PROPRIETARY, RECENT_PER_SOURCE_LIMIT)
        } else {
            emptyList()
        }
    } else {
        modernRecentFlow.value = emptyList()
        fdroidRecentFlow.value = emptyList()
        izzyRecentFlow.value = emptyList()
        proprietaryRecentFlow.value = emptyList()
    }
    recentlyUpdatedFlow.value =
        modernRecentFlow.value + proprietaryRecentFlow.value + fdroidRecentFlow.value +
            izzyRecentFlow.value

    if (AppSource.PLAYSTORE !in enabled) {
        isLoadingHomeFlow.value = false
        return
    }

    val clusters = play.homeClusters()
    playFullClustersFlow.value = clusters
        .filter { it.apps.isNotEmpty() }
        .take(PLAY_CLUSTER_LIMIT)
        .associate { "play-${it.title}" to it.apps }
    playSectionsFlow.value = clusters
        .filter { it.apps.isNotEmpty() }
        .take(PLAY_CLUSTER_LIMIT)
        .map { AppSection("play-${it.title}", it.title, it.apps.take(CAROUSEL_LIMIT)) }

    if (playSectionsFlow.value.isEmpty()) {
        // No account, or Play changed its stream shape. A top chart is one request and
        // still gives the screen something beyond this repo's own dozen apps.
        val chart = play.topChart()
        if (chart.isNotEmpty()) {
            playFullClustersFlow.value = mapOf(
                "play-top" to chart,
            )
            playSectionsFlow.value = listOf(
                AppSection(
                    id = "play-top",
                    title = context.getString(R.string.section_play_top_charts),
                    apps = chart.take(CAROUSEL_LIMIT),
                )
            )
        }
    }
    isLoadingHomeFlow.value = false
}

internal fun AppStoreViewModel.buildSections(
    modern: List<UnifiedApp>,
    modernRecent: List<UnifiedApp>,
    proprietaryRecent: List<UnifiedApp>,
    fdroidRecent: List<UnifiedApp>,
    izzyRecent: List<UnifiedApp>,
    playSections: List<AppSection>,
    accrescent: List<UnifiedApp>,
    categoryApps: List<UnifiedApp>,
    category: String?,
): List<AppSection> = buildList {
    // A chosen category replaces the browsing rows: the user asked a narrow question
    // and a wall of unrelated carousels underneath it is just noise.
    if (category != null) {
        add(
            AppSection(
                id = "category",
                title = category,
                apps = categoryApps,
                layout = SectionLayout.LIST,
                subtitle = context.getString(R.string.section_category_subtitle),
            )
        )
        return@buildList
    }
    // Each offline repo gets its own row — Modern Apps, the proprietary mirror and
    // F-Droid are never mixed into one "recent" list.
    if (modernRecent.isNotEmpty()) {
        add(
            AppSection(
                id = "modern-recent",
                title = context.getString(R.string.section_modern_apps_recent),
                apps = modernRecent,
                subtitle = context.getString(R.string.section_modern_apps_recent_subtitle),
            )
        )
    } else if (modern.isNotEmpty()) {
        add(
            AppSection(
                id = "modern",
                title = context.getString(R.string.section_modern_apps),
                apps = modern,
                subtitle = context.getString(R.string.section_modern_apps_subtitle),
            )
        )
    }
    if (proprietaryRecent.isNotEmpty()) {
        add(
            AppSection(
                id = "proprietary-recent",
                title = context.getString(R.string.section_proprietary_recent),
                apps = proprietaryRecent,
                subtitle = context.getString(R.string.section_proprietary_recent_subtitle),
            )
        )
    }
    addAll(playSections)
    if (accrescent.isNotEmpty()) {
        add(
            AppSection(
                id = "accrescent",
                title = context.getString(R.string.section_accrescent),
                apps = accrescent,
                subtitle = context.getString(R.string.section_accrescent_subtitle),
            )
        )
    }
    if (fdroidRecent.isNotEmpty()) {
        add(
            AppSection(
                id = "fdroid-recent",
                title = context.getString(R.string.section_fdroid_recent),
                apps = fdroidRecent,
                subtitle = context.getString(R.string.section_fdroid_recent_subtitle),
            )
        )
    }
    if (izzyRecent.isNotEmpty()) {
        add(
            AppSection(
                id = "izzy-recent",
                title = context.getString(R.string.section_izzy_recent),
                apps = izzyRecent,
                subtitle = context.getString(R.string.section_izzy_recent_subtitle),
            )
        )
    }
}

/**
 * Populate the offline catalogues the first time the store is opened.
 *
 * Nothing else fetches them on startup: the periodic
 * [com.vayunmathur.appstore.work.UpdateCheckWorker] may be hours away. That
 * left a fresh install showing an empty store until the user thought to pull to refresh.
 *
 * Keyed off the catalogue being empty rather than a "first run" flag, so it also recovers
 * a store whose first sync failed or whose data was cleared - and so it stays quiet on
 * every later launch, when re-fetching two full catalogues on the user's connection would
 * be a poor trade for data that is at most a few hours stale.
 */
internal fun AppStoreViewModel.syncIfNeverSynced(enabled: Set<AppSource>) {
    if (recentlyUpdatedFlow.value.isNotEmpty()) return
    // Nothing to fetch if all four offline sources are switched off; syncSources() would only
    // report "all sources off" at someone who never asked for a sync.
    val offlineSources = setOf(
        DefaultRepos.FDROID.source, DefaultRepos.MODERN_APPS.source, DefaultRepos.PROPRIETARY.source,
        DefaultRepos.IZZY.source,
    )
    if (enabled.intersect(offlineSources).isEmpty()) return
    syncSources()
}

/**
 * Fill the Browse page with one home section's full catalogue.
 *
 * Each home carousel is a capped slice — the 20 newest builds, the first 20 tiles.
 * Browse lists literally every app behind that section, A–Z: the whole offline repo
 * for an offline row, the whole paged catalogue for Accrescent, the uncapped cluster
 * for a Play row. The rows use the same [AppRow] as search.
 */
fun AppStoreViewModel.openBrowse(section: AppSection) {
    browseJob?.cancel()
    browseSectionFlow.value = section.id
    browseTitleFlow.value = section.title
    browseAppsFlow.value = emptyList()
    isLoadingBrowseFlow.value = true
    browseJob = viewModelScope.launch {
        val apps = when {
            section.id == "category" ->
                catalog.allByCategory(section.title)
            section.id == "modern" || section.id == "modern-recent" ->
                catalog.allBySource(AppSource.MODERN_APPS)
            section.id == "proprietary-recent" ->
                catalog.allBySource(AppSource.PROPRIETARY)
            section.id == "fdroid-recent" ->
                catalog.allBySource(AppSource.FDROID)
            section.id == "izzy-recent" ->
                catalog.allBySource(AppSource.IZZY)
            section.id == "accrescent" ->
                accrescent.allListings()
            else ->
                playFullClustersFlow.value[section.id] ?: section.apps
        }
        // A section tapped twice in a row re-resolves: the visible row may predate a
        // sync, and stale full lists are worse than a re-read.
        browseAppsFlow.value = apps.sortedBy { it.name.lowercase() }
        isLoadingBrowseFlow.value = false
    }
}

/** Re-download both offline catalogues, then reload the home rows from them. */
fun AppStoreViewModel.syncSources() {
    if (isSyncingFlow.value) return
    viewModelScope.launch {
        // The persisted choice, not the flow's optimistic default: a manual sync fired
        // on a cold start must not re-fetch a source the user switched off (#845).
        val enabled = settings.readEnabledSources()
        isSyncingFlow.value = true
        val report = catalog.sync(enabled) { step ->
            statusMessageFlow.value = context.getString(
                when (step) {
                    SyncStep.FDROID -> R.string.sync_step_fdroid
                    SyncStep.IZZY -> R.string.sync_step_izzy
                    SyncStep.MODERN_APPS -> R.string.sync_step_modern_apps
                    SyncStep.PROPRIETARY -> R.string.sync_step_proprietary
                }
            )
        }
        statusMessageFlow.value = ""
        isSyncingFlow.value = false

        AppMessages.show(
            when {
                report.allSkipped -> context.getString(R.string.sync_all_sources_off)
                !report.anyFailed -> context.getString(
                    R.string.sync_done,
                    (report.fdroidCount ?: 0) + (report.modernCount ?: 0) +
                        (report.proprietaryCount ?: 0) + (report.izzyCount ?: 0),
                )
                report.fdroidCount == null && report.modernCount == null &&
                    report.proprietaryCount == null && report.izzyCount == null ->
                    context.getString(R.string.sync_failed_all)
                report.fdroidCount == null -> context.getString(R.string.sync_failed_fdroid)
                report.izzyCount == null && AppSource.IZZY in enabled ->
                    context.getString(R.string.sync_failed_izzy)
                report.proprietaryCount == null ->
                    context.getString(R.string.sync_failed_proprietary)
                else -> context.getString(R.string.sync_failed_modern_apps)
            }
        )

        categoriesFlow.value = catalog.categories()
        loadHome(enabled)
        loadAccrescent(enabled)
        installedRepo.refresh()
    }
}
