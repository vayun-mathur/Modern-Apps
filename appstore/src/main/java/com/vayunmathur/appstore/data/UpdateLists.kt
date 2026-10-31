package com.vayunmathur.appstore.data

/**
 * One update check's candidates, split by what "Update all" may touch (#885).
 *
 * The merge itself is unchanged (source precedence, device re-check); only the
 * tail partitions. [eligible] is what a bulk run attempts. [foreign] is newer
 * versions of packages another store (or adb) installed — a bulk run skips
 * these rather than spending a system confirmation dialog (or a doomed
 * failure) on each one, but an explicit tap still installs them.
 */
data class PartitionedUpdates(
    val eligible: List<UnifiedApp> = emptyList(),
    val foreign: List<UnifiedApp> = emptyList(),
) {
    /**
     * Everything the store may still draw as an update: the bulk run's targets
     * plus the individually-updatable foreign rows.
     */
    val actionable: List<UnifiedApp>
        get() = (eligible + foreign).sortedBy { it.name.lowercase() }
}

/**
 * Merge raw per-source candidates the way the screens always have, then
 * partition for bulk updates. Pure Kotlin (no Android) so it unit-tests
 * without Robolectric.
 */
fun partitionUpdates(
    pending: List<UnifiedApp>,
    installed: Map<String, InstalledInfo>,
    enabled: Set<AppSource>,
    ownPackage: String,
): PartitionedUpdates {
    val fresh = pending
        // A disabled source contributes nothing, even if its in-memory snapshot
        // has not been cleared yet (issue #845).
        .filter { it.source in enabled }
        // The surviving row's source decides which download-and-verify path the
        // update takes, so this stays the same precedence search uses.
        .sortedBy { it.source.priority }
        .distinctBy { it.packageName }
        // Re-check against what is on the device rather than trusting the
        // lists: the Play half is a snapshot from the last network check.
        .filter { app ->
            val installedVersion = installed[app.packageName]?.versionCode ?: return@filter false
            app.versionCode > installedVersion
        }
        .sortedBy { it.name.lowercase() }
    val (foreign, eligible) = fresh.partition { app ->
        val info = installed.getValue(app.packageName)
        !com.vayunmathur.appstore.domain.UpdateEligibility.installedByStore(
            info.installerPackage,
            info.updateOwnerPackage,
            ownPackage,
        )
    }
    return PartitionedUpdates(eligible = eligible, foreign = foreign)
}
