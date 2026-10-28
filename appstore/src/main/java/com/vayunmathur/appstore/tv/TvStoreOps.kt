package com.vayunmathur.appstore.tv

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallFailureBatch
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.play.PlayAuthState
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.searchCandidate
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.domain.SearchRanking
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Search hub + updates hub bodies ----
// Moved from TvStoreViewModel.kt (FileLength split); behavior identical.

/**
 * The offline catalogue rows that are all the same shape: recent builds with a title
 * and subtitle, no fallback. Modern Apps is not here — its home row falls back to an
 * A-Z slice when nothing is recent, so it stays inline in `loadHome`.
 */
internal suspend fun TvStoreViewModel.offlineRows(enabled: Set<AppSource>, limit: Int): List<TvSection> =
    buildList {
        for (row in OFFLINE_ROWS) {
            if (row.source in enabled) {
                catalog.recentlyUpdatedBySource(row.source, limit)
                    .takeIf { it.isNotEmpty() }?.let {
                        add(
                            TvSection(
                                id = row.sectionId,
                                title = context.getString(row.titleRes),
                                subtitle = context.getString(row.subtitleRes),
                                source = row.source,
                                apps = it,
                            )
                        )
                    }
            }
        }
    }

private data class OfflineRow(
    val source: AppSource,
    val sectionId: String,
    val titleRes: Int,
    val subtitleRes: Int,
)

private val OFFLINE_ROWS = listOf(
    OfflineRow(
        AppSource.PROPRIETARY,
        "proprietary-recent",
        R.string.section_proprietary_recent,
        R.string.section_proprietary_recent_subtitle,
    ),
    OfflineRow(
        AppSource.FDROID,
        "fdroid-recent",
        R.string.section_fdroid_recent,
        R.string.section_fdroid_recent_subtitle,
    ),
    OfflineRow(
        AppSource.IZZY,
        "izzy-recent",
        R.string.section_izzy_recent,
        R.string.section_izzy_recent_subtitle,
    ),
)

/**
 * Two-wave search: the on-disk catalogues answer instantly, then Accrescent's cached
 * listings and Play's reply merge in. Mirrors the phone's `setSearchImpl`: the local
 * half is published first so the user never waits on the network to see something.
 */
internal fun TvStoreViewModel.setSearchImpl(query: String) {
    queryFlow.value = query
    searchJob?.cancel()
    if (query.isBlank()) {
        searchResultsFlow.value = emptyList()
        hasSearchedFlow.value = false
        isSearchingFlow.value = false
        return
    }
    searchJob = viewModelScope.launch {
        delay(SEARCH_DEBOUNCE_MS)
        isSearchingFlow.value = true
        searchPlayErrorFlow.value = ""
        val enabled = enabledSourcesFlow.value

        val local = catalog.searchLocal(query)
        searchResultsFlow.value = SearchRanking.rank(local, query) { it.searchCandidate() }

        // Accrescent has no search RPC: client-side over the listings already cached
        // from the home carousel, so no extra network round-trip.
        val accrescentResults =
            if (AppSource.ACCRESCENT in enabled) accrescent.search(query) else emptyList()
        // Play may refuse (429): the auth state records the refusal and the local half
        // stays on screen — surfaced above the list, never as a silent gap.
        val remote = if (AppSource.PLAYSTORE in enabled) play.search(query) else emptyList()
        searchPlayErrorFlow.value = (play.authState.value as? PlayAuthState.Error)
            ?.let { context.getString(R.string.play_unavailable, it.message) }
            .orEmpty()
        searchResultsFlow.value = SearchRanking.rank(
            (local + remote + accrescentResults)
                .sortedBy { it.source.priority }
                .distinctBy { it.packageName },
            query,
        ) { it.searchCandidate() }
        isSearchingFlow.value = false
        hasSearchedFlow.value = true
    }
}

/**
 * Recheck every enabled source for newer builds of installed packages. Mirrors the
 * phone's refresh + `checkForUpdatesImpl`: re-download the offline catalogues (so new
 * versions and new index data - e.g. TV verdicts - arrive without waiting for the 12h
 * worker), then Play only for packages no offline source lists, then Accrescent's
 * allowlist refresh and per-package update info.
 */
internal fun TvStoreViewModel.checkForUpdatesImpl() {
    if (isCheckingUpdatesFlow.value) return
    viewModelScope.launch {
        try {
            val enabled = enabledSourcesFlow.value
            isCheckingUpdatesFlow.value = true
            statusMessageFlow.value = context.getString(R.string.updates_checking)

            catalog.sync(enabled)
            loadHome()
            installedRepo.refresh()
            catalogUpdatesFlow.value = catalog.updatesFor(installedRepo.updatable.value)

            // Direct DB read, not packageIndex.value: the flow's StateFlow cache starts
            // empty until Room emits, so a cold-start check reading .value sees no
            // packages and asks Play about every installed app.
            val index = catalog.indexSnapshot()
            val installed = installedRepo.updatable.value
            if (AppSource.PLAYSTORE in enabled) {
                val playCandidates = installed
                    .filter { it.packageName !in index }
                    .map { it.packageName }
                // Fail soft: a refused/failed lookup (null) keeps the previous Play
                // rows rather than wiping valid updates. Only an answered lookup
                // (even an empty one) clears.
                val remote = play.detailsOrNull(playCandidates)
                if (remote != null) {
                    val byPackage = remote.associateBy { it.packageName }
                    playUpdatesFlow.value = installed.mapNotNull { inst ->
                        byPackage[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
                    }
                }
            } else {
                // A check with Play off must not leave a stale snapshot behind (#845).
                playUpdatesFlow.value = emptyList()
            }

            if (AppSource.ACCRESCENT in enabled) {
                accrescent.refreshRepoData()
                val accrescentIds = accrescent.appIds()
                accrescentUpdatesFlow.value = installed
                    .filter { it.packageName in accrescentIds }
                    .mapNotNull { inst -> accrescentUpdate(inst.packageName, inst.versionCode) }
            } else {
                accrescentUpdatesFlow.value = emptyList()
            }

            lastUpdateCheckFlow.value = System.currentTimeMillis()
            statusMessageFlow.value = ""
        } finally {
            // An escaping failure must not wedge the tab mid-check.
            isCheckingUpdatesFlow.value = false
        }
    }
}

/**
 * Update everything, sequentially, and report the run once when it is over. Mirrors the
 * phone's `updateAllImpl`: concurrent system dialogs would bury the user in prompts,
 * and failures are collected and summarised instead of one snackbar per app.
 */
internal fun TvStoreViewModel.updateAllImpl() {
    if (updateAllJob?.isActive == true) return
    updateAllJob = viewModelScope.launch {
        val batch = updatesUi.value.updates
        if (batch.isEmpty()) return@launch
        var started = 0
        val failures = mutableListOf<String>()
        var repeats = 0
        var previousReason: String? = null
        InstallFailureBatch.begin()
        try {
            for ((index, app) in batch.withIndex()) {
                statusMessageFlow.value = context.getString(
                    R.string.updates_progress, app.name, index + 1, batch.size
                )
                val outcome = installer.install(app)
                if (outcome.started) {
                    started++
                    previousReason = null
                    repeats = 0
                } else {
                    val failure = when (val verdict = outcome.verification) {
                        is VerificationResult.Rejected -> verdict.reason
                        is VerificationResult.Unverified -> verdict.reason
                        is VerificationResult.Verified ->
                            context.getString(R.string.install_failure_unknown)
                    }
                    failures += failure
                    repeats = if (failure == previousReason) repeats + 1 else 1
                    previousReason = failure
                    if (repeats >= REPEATED_FAILURE_LIMIT) break
                }
            }
            // PackageInstaller commits asynchronously: wait for the last verdict before
            // summarising, or it lands on its own afterwards.
            delay(INSTALL_SETTLE_MS)
        } finally {
            statusMessageFlow.value = ""
            failures += InstallFailureBatch.end()
        }

        AppMessages.show(
            if (failures.isEmpty()) {
                context.resources.getQuantityString(
                    R.plurals.updates_batch_installed, started, started
                )
            } else {
                context.getString(
                    R.string.updates_batch_partial,
                    started,
                    batch.size,
                    failures.first(),
                )
            },
            duration = if (failures.isEmpty()) {
                AppMessages.Duration.Short
            } else {
                AppMessages.Duration.Long
            },
        )
        installedRepo.refresh()
    }
}

/**
 * The available Accrescent update for [packageName] as an installable listing, or null
 * when there is none. Same shape as the phone's helper: the version code is the
 * update's, so the updates filter keeps it only while genuinely newer.
 */
private suspend fun TvStoreViewModel.accrescentUpdate(
    packageName: String,
    currentVersionCode: Long,
): UnifiedApp? {
    val update = runCatching {
        accrescent.updateInfo(packageName, currentVersionCode)
    }.getOrNull() ?: return null
    val details = accrescent.details(packageName) ?: UnifiedApp(
        packageName = packageName,
        source = AppSource.ACCRESCENT,
        name = packageName.substringAfterLast('.'),
    )
    return details.copy(versionCode = update.versionCode, versionName = update.versionName)
}

private const val SEARCH_DEBOUNCE_MS = 350L
private const val INSTALL_SETTLE_MS = 1_500L
/** How many times in a row an update may fail for the same reason before the run ends. */
private const val REPEATED_FAILURE_LIMIT = 3
