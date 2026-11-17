package com.vayunmathur.appstore.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.SourceFilter
import kotlinx.coroutines.launch

// ---- Library + Accrescent attribution ----
// Moved from TvStoreViewModel.kt (FileLength split); behavior identical.

// --- Accrescent home row ------------------------------------------------------------
/**
 * Refresh Accrescent's signed allowlist and its home listings. Fail soft like the
 * phone's `loadAccrescent`: a network blip leaves the previous rows in place rather
 * than emptying them.
 */
internal suspend fun TvStoreViewModel.loadAccrescent(enabled: Set<AppSource> = enabledSourcesFlow.value) {
    if (AppSource.ACCRESCENT !in enabled) return
    accrescent.refreshRepoData()
    // The same response tells us which packages Accrescent vouches for, which the
    // library uses for attribution. Fail soft like the phone: a blip keeps the
    // previous answer rather than emptying the library.
    val ids = accrescent.appIds()
    if (ids.isNotEmpty()) accrescentPackagesFlow.value = ids
    val page = accrescent.listApps()
    if (page.apps.isNotEmpty()) accrescentAppsFlow.value = page.apps
}

internal fun TvStoreViewModel.setLibraryFilter(filter: SourceFilter) {
    libraryFilterFlow.value = filter
}

/** Re-read the device's installed set, for the library screen appearing. */
internal fun TvStoreViewModel.refreshInstalled() {
    viewModelScope.launch {
        installedRepo.refresh()
        refreshPlayInstalledPackages()
    }
}

/**
 * Ask Play which installed packages it actually hosts, for library attribution.
 *
 * Mirrors the phone's `refreshPlayInstalledPackages`: only packages no offline
 * source lists are worth asking about, and a failed lookup keeps the previous
 * answer rather than emptying the library.
 */
internal suspend fun TvStoreViewModel.refreshPlayInstalledPackages(
    enabled: Set<AppSource> = enabledSourcesFlow.value,
) {
    if (AppSource.PLAYSTORE !in enabled) {
        playInstalledPackagesFlow.value = emptySet()
        return
    }
    val index = catalog.indexSnapshot()
    val candidates = installedRepo.apps.value
        .map { it.packageName }
        .filter { it !in index }
    if (candidates.isEmpty()) {
        playInstalledPackagesFlow.value = emptySet()
        return
    }
    play.detailsOrNull(candidates)?.map { it.packageName }?.toSet()?.let {
        playInstalledPackagesFlow.value = it
    }
}

/** An installed package as a listing, for the library screen. */
internal fun InstalledInfo.toTvListing(source: AppSource) = UnifiedApp(
    packageName = packageName,
    source = source,
    name = name,
    versionName = versionName,
    versionCode = versionCode,
    lastUpdated = lastUpdateTime,
)
