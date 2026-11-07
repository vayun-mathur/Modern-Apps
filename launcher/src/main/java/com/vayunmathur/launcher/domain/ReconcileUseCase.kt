package com.vayunmathur.launcher.domain

/**
 * Decides what to do with saved items whose target may no longer be there.
 *
 * Run on cold start and on every package/profile event. The distinction that matters is
 * **uninstalled** versus **unavailable**: a work profile turned off, or an app on
 * unmounted storage, reports as gone but comes back. Deleting on unavailable is the
 * classic launcher bug where pausing a work profile permanently wipes half the home
 * screen, so those items are hidden and restored instead.
 *
 * Pure, and returns actions rather than performing them, so the whole matrix of
 * installed/unavailable/missing against already-hidden is unit-testable.
 */
object ReconcileUseCase {

    /** An item reduced to what reconciliation needs to judge it. */
    data class Item(
        val id: Long,
        val type: LauncherItemType,
        val packageName: String?,
        val profileSerial: Long,
        val appWidgetId: Int?,
        val hidden: Boolean,
    ) {
        val key: PackageKey? get() = packageName?.let { PackageKey(it, profileSerial) }
    }

    sealed interface Action {
        /** The target is gone for good. Drop the row. */
        data class Delete(val id: Long) : Action

        /** The target is temporarily absent, or has come back. Keep the row, change visibility. */
        data class SetHidden(val id: Long, val hidden: Boolean) : Action
    }

    /**
     * @param installed packages present and usable right now.
     * @param unavailable packages that exist but cannot be launched at the moment — a
     *   paused or quiet work profile, an app on ejected storage. Callers must not fold
     *   these into [installed] or the icon disappears; nor omit them, or it is deleted.
     * @param boundWidgetIds ids `AppWidgetHost.getAppWidgetIds()` still recognises. An id
     *   the host has forgotten can never be rendered, so its row is dead weight.
     */
    fun reconcile(
        items: List<Item>,
        installed: Set<PackageKey>,
        unavailable: Set<PackageKey>,
        boundWidgetIds: Set<Int>,
        unavailableProfiles: Set<Long> = emptySet(),
    ): List<Action> = items.mapNotNull { item ->
        when (item.type) {
            // A folder's existence depends on its children, which FolderRules owns.
            LauncherItemType.FOLDER -> null

            LauncherItemType.APPWIDGET ->
                if (item.appWidgetId == null || item.appWidgetId !in boundWidgetIds) {
                    Action.Delete(item.id)
                } else {
                    null
                }

            LauncherItemType.APPLICATION, LauncherItemType.DEEP_SHORTCUT -> {
                val key = item.key
                when {
                    key == null -> Action.Delete(item.id)
                    // A profile that is off (quiet mode) at cold start has sent
                    // no onPackagesUnavailable callback yet, and its packages
                    // cannot be enumerated to fill [unavailable]. The profile
                    // itself is the only reliable signal, so hide by profile:
                    // deleting here wipes every work-profile icon whenever
                    // the launcher restarts while the profile is paused.
                    item.profileSerial in unavailableProfiles ->
                        Action.SetHidden(item.id, true).takeUnless { item.hidden }
                    key in unavailable -> Action.SetHidden(item.id, true).takeUnless { item.hidden }
                    key in installed -> Action.SetHidden(item.id, false).takeIf { item.hidden }
                    else -> Action.Delete(item.id)
                }
            }
        }
    }
}
