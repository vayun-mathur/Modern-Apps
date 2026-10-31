package com.vayunmathur.appstore.util

import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.CAROUSEL_LIMIT
import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.INSTALL_SETTLE_MS
import com.vayunmathur.appstore.util.AppStoreViewModel.Companion.REPEATED_FAILURE_LIMIT
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallFailureBatch
import com.vayunmathur.appstore.data.installer.SessionInstaller
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Updates ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

internal fun AppStoreViewModel.checkForUpdatesImpl() {
    if (isCheckingUpdatesFlow.value) return
    viewModelScope.launch {
        try {
            // The persisted choice, not the flow's optimistic default: this runs from
            // HomePage on every cold start, before DataStore has delivered the stored
            // value, and the default has every source switched on (#845).
            val enabled = settings.readEnabledSources()
            isCheckingUpdatesFlow.value = true
            statusMessageFlow.value = context.getString(R.string.updates_checking)

            installedRepo.refresh()
            catalogUpdatesFlow.value = catalog.updatesFor(installedRepo.updatable.value)

            // Only ask Play about packages neither offline source lists — for the rest the
            // catalogue already answered, and Play would just re-answer it over the network.
            // A direct DB read, not packageIndex.value: the flow's StateFlow cache starts
            // empty until Room emits, so a cold-start check reading .value sees no packages
            // and asks Play about every installed app — including ones the offline
            // catalogue lists. Those phantom Play rows then vanish on the next refresh.
            val index = catalog.indexSnapshot()
            val installed = installedRepo.updatable.value
            if (AppSource.PLAYSTORE in enabled) {
                val playCandidates = installed
                    .filter { it.packageName !in index }
                    .map { it.packageName }

                // Fail soft: a refused/failed lookup (null) keeps the previous Play rows
                // rather than wiping valid updates — "Play didn't answer" is not "Play
                // has no updates". Only an answered lookup (even an empty one) clears.
                val remote = play.detailsOrNull(playCandidates)
                if (remote != null) {
                    val byPackage = remote.associateBy { it.packageName }
                    playUpdatesFlow.value = installed.mapNotNull { inst ->
                        byPackage[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
                    }
                    // The same response tells us which of these packages Play actually
                    // hosts, which the library uses to tell a genuine Play app from a
                    // sideloaded one.
                    if (byPackage.isNotEmpty()) playInstalledPackagesFlow.value = byPackage.keys.toSet()
                }
            } else {
                // A cold-start check with Play off must not leave a stale snapshot behind.
                playUpdatesFlow.value = emptyList()
                playInstalledPackagesFlow.value = emptySet()
            }

            // Accrescent: refresh the signed allowlist, then ask its API for a newer build of
            // each installed package it vouches for.
            if (AppSource.ACCRESCENT in enabled) {
                accrescent.refreshRepoData()
                val accrescentIds = accrescent.appIds()
                if (accrescentIds.isNotEmpty()) accrescentPackagesFlow.value = accrescentIds
                accrescentUpdatesFlow.value = installed
                    .filter { it.packageName in accrescentIds }
                    .mapNotNull { inst -> accrescentUpdate(inst.packageName, inst.versionCode) }
            } else {
                accrescentUpdatesFlow.value = emptyList()
                accrescentPackagesFlow.value = emptySet()
            }

            lastUpdateCheckFlow.value = System.currentTimeMillis()
            statusMessageFlow.value = ""
        } finally {
            // An escaping failure (e.g. gRPC/Room) must not wedge the tab mid-check (#812).
            isCheckingUpdatesFlow.value = false
        }
    }
}

/**
 * Update everything, and report the run once when it is over.
 *
 * Only the eligible batch runs: packages this store has never installed would
 * each cost a system confirmation dialog (or a doomed failure) and slow the
 * whole run while the user waits for them to fail, so they stay listed for an
 * explicit tap instead (#885). Sequential on purpose for the same reason —
 * firing even the eligible ones concurrently buries the user in prompts.
 *
 * The reporting is the other half of issue #630. A run used to say nothing itself and
 * let each app's failure raise its own snackbar, so a phone whose updates were all
 * failing the same way — Play refusing the lot for going too fast, typically — showed
 * the user the same error once per app. Now the failures are collected and summarised,
 * and a reason that keeps coming back ends the run rather than being demonstrated
 * another dozen times.
 */
internal fun AppStoreViewModel.updateAllImpl() {
    if (updateAllJob?.isActive == true) return
    updateAllJob = viewModelScope.launch {
        // Eligible only: foreign installs are individually updatable but never
        // bulk-attempted, and ignored packages are excluded everywhere (#885).
        val batch = partitionedUpdates.value.eligible
        if (batch.isEmpty()) return@launch
        val run = UpdateRun()
        InstallFailureBatch.begin()
        try {
            for ((index, app) in batch.withIndex()) {
                statusMessageFlow.value = context.getString(
                    R.string.updates_progress, app.name, index + 1, batch.size
                )
                if (run.record(this@updateAllImpl, installer.install(app))) break
            }
            // PackageInstaller commits asynchronously, so the last app's real verdict
            // is still on its way. Wait for it before summarising, or it lands on its
            // own afterwards — which is the repetition this is here to stop.
            delay(INSTALL_SETTLE_MS)
        } finally {
            statusMessageFlow.value = ""
            run.failures += InstallFailureBatch.end()
        }

        AppMessages.show(
            if (run.failures.isEmpty()) {
                context.resources.getQuantityString(
                    R.plurals.updates_batch_installed, run.started, run.started
                )
            } else {
                context.getString(
                    R.string.updates_batch_partial,
                    run.started,
                    batch.size,
                    run.failures.first(),
                )
            },
            duration = if (run.failures.isEmpty()) {
                AppMessages.Duration.Short
            } else {
                AppMessages.Duration.Long
            },
        )
        installedRepo.refresh()
    }
}

private class UpdateRun {
    val failures = mutableListOf<String>()
    var started = 0
    private var repeats = 0
    private var previousReason: String? = null

    /**
     * Records one install outcome. Returns true when the run should stop:
     * the same reason has failed [REPEATED_FAILURE_LIMIT] times in a row.
     */
    fun record(viewModel: AppStoreViewModel, outcome: SessionInstaller.Outcome): Boolean {
        if (outcome.started) {
            started++
            previousReason = null
            repeats = 0
            return false
        }
        val failure = viewModel.reason(outcome.verification)
        failures += failure
        repeats = if (failure == previousReason) repeats + 1 else 1
        previousReason = failure
        return repeats >= REPEATED_FAILURE_LIMIT
    }
}

/** What to tell the user a failed install went wrong with. */
internal fun AppStoreViewModel.reason(verification: VerificationResult): String = when (verification) {
    is VerificationResult.Rejected -> verification.reason
    is VerificationResult.Unverified -> verification.reason
    is VerificationResult.Verified -> context.getString(R.string.install_failure_unknown)
}

/**
 * The available Accrescent update for [packageName] as an installable listing, or null when
 * there is none. The version code is the update's, so the [updates] filter keeps it only
 * while it is genuinely newer than what is installed.
 */
internal suspend fun AppStoreViewModel.accrescentUpdate(packageName: String, currentVersionCode: Long): UnifiedApp? {
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

/**
 * Refresh Accrescent's signed allowlist and its home listings. Both fail soft: a network
 * blip leaves the previous rows and attribution set in place rather than emptying them.
 */
internal suspend fun AppStoreViewModel.loadAccrescent(enabled: Set<AppSource> = enabledSources.value) {
    if (AppSource.ACCRESCENT !in enabled) return
    accrescent.refreshRepoData()
    val ids = accrescent.appIds()
    if (ids.isNotEmpty()) accrescentPackagesFlow.value = ids
    val page = accrescent.listApps()
    if (page.apps.isNotEmpty()) accrescentAppsFlow.value = page.apps.take(CAROUSEL_LIMIT)
}
