package com.vayunmathur.appstore.util

import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.CAROUSEL_LIMIT
import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.PLAY_CLUSTER_LIMIT
import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.RECENT_PER_SOURCE_LIMIT
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.SyncReport
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
    loadOfflineRecents(enabled)
    if (AppSource.PLAYSTORE !in enabled) {
        isLoadingHomeFlow.value = false
        return
    }
    loadPlayHomeSections()
    isLoadingHomeFlow.value = false
}

private suspend fun AppStoreViewModel.loadOfflineRecents(enabled: Set<AppSource>) {
    suspend fun recentOrEmpty(source: AppSource): List<UnifiedApp> =
        if (source in enabled) catalog.recentlyUpdatedBySource(source, RECENT_PER_SOURCE_LIMIT) else emptyList()
    modernRecentFlow.value = recentOrEmpty(AppSource.MODERN_APPS)
    fdroidRecentFlow.value = recentOrEmpty(AppSource.FDROID)
    izzyRecentFlow.value = recentOrEmpty(AppSource.IZZY)
    proprietaryRecentFlow.value = recentOrEmpty(AppSource.PROPRIETARY)
    recentlyUpdatedFlow.value =
        modernRecentFlow.value + proprietaryRecentFlow.value + fdroidRecentFlow.value +
            izzyRecentFlow.value
}

private suspend fun AppStoreViewModel.loadPlayHomeSections() {
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
            playFullClustersFlow.value = mapOf("play-top" to chart)
            playSectionsFlow.value = listOf(
                AppSection(
                    id = "play-top",
                    title = context.getString(R.string.section_play_top_charts),
                    apps = chart.take(CAROUSEL_LIMIT),
                )
            )
        }
    }
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
): List<AppSection> {
    // A chosen category replaces the browsing rows: the user asked a narrow question
    // and a wall of unrelated carousels underneath it is just noise.
    if (category != null) {
        return listOf(
            AppSection(
                id = "category",
                title = category,
                apps = categoryApps,
                layout = SectionLayout.LIST,
                subtitle = context.getString(R.string.section_category_subtitle),
            )
        )
    }
    // Each offline repo gets its own row — Modern Apps, the proprietary mirror and
    // F-Droid are never mixed into one "recent" list.
    return listOfNotNull(
        modernSection(modern, modernRecent),
        recentSection("proprietary-recent", R.string.section_proprietary_recent, proprietaryRecent),
    ) + playSections + listOfNotNull(
        recentSection(
            "accrescent", R.string.section_accrescent, accrescent,
            subtitleRes = R.string.section_accrescent_subtitle,
        ),
        recentSection("fdroid-recent", R.string.section_fdroid_recent, fdroidRecent),
        recentSection("izzy-recent", R.string.section_izzy_recent, izzyRecent),
    )
}

private fun AppStoreViewModel.modernSection(
    modern: List<UnifiedApp>,
    modernRecent: List<UnifiedApp>,
): AppSection? = when {
    modernRecent.isNotEmpty() -> AppSection(
        id = "modern-recent",
        title = context.getString(R.string.section_modern_apps_recent),
        apps = modernRecent,
        subtitle = context.getString(R.string.section_modern_apps_recent_subtitle),
    )
    modern.isNotEmpty() -> AppSection(
        id = "modern",
        title = context.getString(R.string.section_modern_apps),
        apps = modern,
        subtitle = context.getString(R.string.section_modern_apps_subtitle),
    )
    else -> null
}

private fun AppStoreViewModel.recentSection(
    id: String,
    titleRes: Int,
    apps: List<UnifiedApp>,
    subtitleRes: Int? = null,
): AppSection? {
    if (apps.isEmpty()) return null
    return AppSection(
        id = id,
        title = context.getString(titleRes),
        apps = apps,
        subtitle = subtitleRes?.let { context.getString(it) },
    )
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
 * Fill the Browse page with the full catalogue behind [sectionId] — the
 * argument of `Route.Browse`.
 *
 * Each home carousel is a capped slice — the 20 newest builds, the first 20
 * tiles. Browse lists literally every app behind that section, A–Z: the whole
 * offline repo for an offline row, the whole paged catalogue for Accrescent,
 * the uncapped cluster for a Play row. The rows use the same [AppRow] as
 * search.
 *
 * The section the id names is looked up in the home state that produced it
 * (its title and, for a Play row, its capped apps are the fallback), so the
 * entry binds only to its route argument: a restored Browse entry re-resolves
 * the section it names instead of reading whatever section a ViewModel flow
 * last held. Known offline ids also resolve if home has not rebuilt the row
 * yet; a category section's name is its title, which only the home row knows,
 * so an unrestorable category id settles empty rather than guessing.
 */
fun AppStoreViewModel.loadBrowse(sectionId: String) {
    browseJob?.cancel()
    val section = home.value.sections.find { it.id == sectionId }
    val title = section?.title ?: browseFallbackTitle(sectionId)
    browseContentFlow.value = BrowseUiState(
        sectionId = sectionId,
        title = title,
        apps = emptyList(),
        isLoading = true,
    )
    browseJob = viewModelScope.launch {
        val apps = when {
            sectionId == "category" ->
                // The row's own list is the fallback: a sync pruning the
                // cache mid-load must not empty a section the user is in.
                section?.let { catalog.allByCategory(it.title).ifEmpty { it.apps } }
                    ?: emptyList()
            sectionId == "modern" || sectionId == "modern-recent" ->
                catalog.allBySource(AppSource.MODERN_APPS)
            sectionId == "proprietary-recent" ->
                catalog.allBySource(AppSource.PROPRIETARY)
            sectionId == "fdroid-recent" ->
                catalog.allBySource(AppSource.FDROID)
            sectionId == "izzy-recent" ->
                catalog.allBySource(AppSource.IZZY)
            sectionId == "accrescent" ->
                accrescent.allListings()
            else ->
                playFullClustersFlow.value[sectionId] ?: section?.apps ?: emptyList()
        }
        // A section tapped twice in a row re-resolves: the visible row may predate a
        // sync, and stale full lists are worse than a re-read.
        browseContentFlow.value = BrowseUiState(
            sectionId = sectionId,
            title = title,
            apps = apps.sortedBy { it.name.lowercase() },
            isLoading = false,
        )
    }
}

/** Title for a known section id when home has not (re)built its row yet. */
private fun AppStoreViewModel.browseFallbackTitle(sectionId: String): String = when (sectionId) {
    "modern", "modern-recent" -> context.getString(R.string.section_modern_apps_recent)
    "proprietary-recent" -> context.getString(R.string.section_proprietary_recent)
    "fdroid-recent" -> context.getString(R.string.section_fdroid_recent)
    "izzy-recent" -> context.getString(R.string.section_izzy_recent)
    "accrescent" -> context.getString(R.string.section_accrescent)
    "play-top" -> context.getString(R.string.section_play_top_charts)
    else -> sectionId.removePrefix("play-")
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
            statusMessageFlow.value = context.getString(syncStepStringRes(step))
        }
        statusMessageFlow.value = ""
        isSyncingFlow.value = false
        AppMessages.show(syncReportMessage(report, enabled))

        categoriesFlow.value = catalog.categories()
        loadHome(enabled)
        loadAccrescent(enabled)
        installedRepo.refresh()
    }
}

private fun syncStepStringRes(step: SyncStep): Int = when (step) {
    SyncStep.FDROID -> R.string.sync_step_fdroid
    SyncStep.IZZY -> R.string.sync_step_izzy
    SyncStep.MODERN_APPS -> R.string.sync_step_modern_apps
    SyncStep.PROPRIETARY -> R.string.sync_step_proprietary
}

private fun AppStoreViewModel.syncReportMessage(report: SyncReport, enabled: Set<AppSource>): String {
    val counts = listOf(report.fdroidCount, report.modernCount, report.proprietaryCount, report.izzyCount)
    return when {
        report.allSkipped -> context.getString(R.string.sync_all_sources_off)
        !report.anyFailed -> context.getString(R.string.sync_done, counts.sumOf { it ?: 0 })
        counts.all { it == null } -> context.getString(R.string.sync_failed_all)
        report.fdroidCount == null -> context.getString(R.string.sync_failed_fdroid)
        report.izzyCount == null && AppSource.IZZY in enabled ->
            context.getString(R.string.sync_failed_izzy)
        report.proprietaryCount == null -> context.getString(R.string.sync_failed_proprietary)
        else -> context.getString(R.string.sync_failed_modern_apps)
    }
}
