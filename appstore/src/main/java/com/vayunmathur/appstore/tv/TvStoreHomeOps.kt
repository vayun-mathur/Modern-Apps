package com.vayunmathur.appstore.tv

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.SyncReport
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

// ---- TV home + first-sync bodies ----
// Moved from TvStoreViewModel.kt (LargeClass split); behavior identical.

private val TV_OFFLINE_SOURCES = setOf(
    DefaultRepos.FDROID.source, DefaultRepos.MODERN_APPS.source,
    DefaultRepos.PROPRIETARY.source, DefaultRepos.IZZY.source,
)

private val TV_OFFLINE_CATALOG_SOURCES = listOf(
    AppSource.FDROID, AppSource.MODERN_APPS, AppSource.PROPRIETARY, AppSource.IZZY,
)

/**
 * Populate the offline catalogues the first time the TV store is opened.
 *
 * Same contract as the phone's `syncIfNeverSynced`: keyed off the catalogue being
 * empty rather than a first-run flag, so it also recovers a store whose first sync
 * failed or whose data was cleared — and stays quiet on every later launch, when
 * re-fetching full catalogues would be a poor trade for at-most-hours-stale data.
 * Progress reports through the existing status line the browse screen already shows.
 */
internal suspend fun TvStoreViewModel.syncIfNeverSyncedImpl() {
    val enabled = enabledSourcesFlow.value
    if (enabled.intersect(TV_OFFLINE_SOURCES).isEmpty()) return
    if (hasOfflineCatalogue()) {
        // The offline gate stays quiet, but Accrescent is network-backed: refresh its
        // allowlist + listings on every cold start that reaches this far, same as the phone.
        loadAccrescent(enabled)
        return
    }
    runFirstSync(enabled)
}

private suspend fun TvStoreViewModel.hasOfflineCatalogue(): Boolean =
    TV_OFFLINE_CATALOG_SOURCES.any { catalog.recentlyUpdatedBySource(it, 1).isNotEmpty() }

private suspend fun TvStoreViewModel.runFirstSync(enabled: Set<AppSource>) {
    isLoadingFlow.value = true
    val report = catalog.sync(enabled) { step ->
        statusMessageFlow.value = context.getString(tvSyncStepStringRes(step))
    }
    statusMessageFlow.value = ""
    isLoadingFlow.value = false
    AppMessages.show(tvSyncReportMessage(report, enabled))
}

private fun tvSyncStepStringRes(step: SyncStep): Int = when (step) {
    SyncStep.FDROID -> R.string.sync_step_fdroid
    SyncStep.IZZY -> R.string.sync_step_izzy
    SyncStep.MODERN_APPS -> R.string.sync_step_modern_apps
    SyncStep.PROPRIETARY -> R.string.sync_step_proprietary
}

private fun TvStoreViewModel.tvSyncReportMessage(report: SyncReport, enabled: Set<AppSource>): String {
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

/**
 * Fill the home rows from the TV-filtered catalogue: one section per source with
 * the phone's own titles and subtitles, capped at two grid rows each.
 *
 * A chosen category replaces the browsing rows, mirroring the phone: the user asked
 * a narrow question and a wall of unrelated sections underneath is just noise.
 */
internal fun TvStoreViewModel.loadHomeImpl() {
    viewModelScope.launch {
        isLoadingFlow.value = true
        val enabled = enabledSourcesFlow.value
        val category = selectedCategoryFlow.value
        if (category != null) {
            showCategoryHome(category)
            isLoadingFlow.value = false
            return@launch
        }
        categoriesFlow.value = catalog.categories()
        val sections = buildTvHomeSections(enabled)
        // Same precedence the phone uses: the surviving row's source decides the
        // download-and-verify path, so cross-source duplicates resolve identically.
        sectionsFlow.value = sections.map { section ->
            section.copy(
                apps = section.apps.sortedBy { it.source.priority }
                    .distinctBy { it.packageName }
            )
        }
        isLoadingFlow.value = false
    }
}

private suspend fun TvStoreViewModel.showCategoryHome(category: String) {
    val apps = categoryAppsFlow.value.ifEmpty { catalog.allByCategory(category) }
    categoryAppsFlow.value = apps
    sectionsFlow.value = listOf(
        TvSection(
            id = "category",
            title = category,
            subtitle = context.getString(R.string.section_category_subtitle),
            apps = apps,
        )
    )
}

private suspend fun TvStoreViewModel.buildTvHomeSections(enabled: Set<AppSource>): List<TvSection> =
    buildList {
        modernHomeSection(enabled)?.let { add(it) }
        addAll(offlineRows(enabled, TvStoreViewModel.ROW_LIMIT))
        addAll(playHomeSections(enabled))
        accrescentHomeSection(enabled)?.let { add(it) }
    }

private suspend fun TvStoreViewModel.modernHomeSection(enabled: Set<AppSource>): TvSection? {
    if (AppSource.MODERN_APPS !in enabled) return null
    val recent = catalog.recentlyUpdatedBySource(AppSource.MODERN_APPS, TvStoreViewModel.ROW_LIMIT)
    if (recent.isNotEmpty()) {
        return TvSection(
            id = "modern-recent",
            title = context.getString(R.string.section_modern_apps_recent),
            subtitle = context.getString(R.string.section_modern_apps_recent_subtitle),
            source = AppSource.MODERN_APPS,
            apps = recent,
        )
    }
    return catalog.allBySource(AppSource.MODERN_APPS).take(TvStoreViewModel.ROW_LIMIT)
        .takeIf { it.isNotEmpty() }?.let {
            TvSection(
                id = "modern",
                title = context.getString(R.string.section_modern_apps),
                subtitle = context.getString(R.string.section_modern_apps_subtitle),
                source = AppSource.MODERN_APPS,
                apps = it,
            )
        }
}

private suspend fun TvStoreViewModel.playHomeSections(enabled: Set<AppSource>): List<TvSection> {
    if (AppSource.PLAYSTORE !in enabled) return emptyList()
    val clusters = play.homeClusters().filter { it.apps.isNotEmpty() }
        .take(TvStoreViewModel.CLUSTER_LIMIT)
    playFullClustersFlow.value = clusters.associate { "play-${it.title}" to it.apps }
    if (clusters.isEmpty()) {
        // No account, or Play changed its stream shape. A top chart is one
        // request and still gives the screen something beyond this repo's
        // own dozen apps. Mirrors the phone's fallback.
        return play.topChart().takeIf { it.isNotEmpty() }?.let { chart ->
            playFullClustersFlow.value = mapOf("play-top" to chart)
            listOf(
                TvSection(
                    id = "play-top",
                    title = context.getString(R.string.section_play_top_charts),
                    source = AppSource.PLAYSTORE,
                    apps = chart.take(TvStoreViewModel.ROW_LIMIT),
                )
            )
        } ?: emptyList()
    }
    return clusters.map { cluster ->
        TvSection(
            id = "play-${cluster.title}",
            title = cluster.title,
            source = AppSource.PLAYSTORE,
            apps = cluster.apps.take(TvStoreViewModel.ROW_LIMIT),
        )
    }
}

private fun TvStoreViewModel.accrescentHomeSection(enabled: Set<AppSource>): TvSection? {
    if (AppSource.ACCRESCENT !in enabled) return null
    return accrescentAppsFlow.value.take(TvStoreViewModel.ROW_LIMIT).takeIf { it.isNotEmpty() }?.let {
        TvSection(
            id = "accrescent",
            title = context.getString(R.string.section_accrescent),
            subtitle = context.getString(R.string.section_accrescent_subtitle),
            source = AppSource.ACCRESCENT,
            apps = it,
        )
    }
}
