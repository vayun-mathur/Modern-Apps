package com.vayunmathur.launcher.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.vayunmathur.launcher.domain.ActivityKey
import com.vayunmathur.launcher.domain.PackageKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One launchable activity, as the drawer and the home screen see it. */
data class AppEntry(
    val componentName: ComponentName,
    val label: String,
    val user: UserHandle,
    val profileSerial: Long,
    /** True for anything outside the primary user, which cannot be uninstalled from here. */
    val isWorkProfile: Boolean,
) {
    val key: ComponentKey get() = ComponentKey(componentName, profileSerial)
    val packageKey: PackageKey get() = PackageKey(componentName.packageName, profileSerial)
}

/**
 * The one registration with [LauncherApps], feeding both the app drawer and
 * reconciliation.
 *
 * [LauncherApps] rather than a `PackageManager` query plus a broadcast receiver: it is
 * the only API that reports work-profile events and `onShortcutsChanged`, and its
 * `getActivityList` already spans every profile. Two separate sources would also mean two
 * lists that can disagree about what is installed, which is exactly the disagreement that
 * makes a launcher lose icons.
 *
 * Availability is tracked separately from installation. A paused work profile or an app on
 * unmounted storage reports as absent from `getActivityList` but is not uninstalled, and
 * only the callback can tell the two apart — so [unavailable] accumulates from
 * `onPackagesUnavailable` and drains on `onPackagesAvailable`, and
 * `ReconcileUseCase` uses it to hide rather than delete.
 */
class LauncherAppsMonitor(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _unavailable = MutableStateFlow<Set<PackageKey>>(emptySet())
    val unavailable: StateFlow<Set<PackageKey>> = _unavailable

    /**
     * Notified whenever the app list changes.
     *
     * The argument names the package whose *artwork* is now stale — an update, rather than an
     * install or removal. Null means "the list changed but no icon needs re-rasterising", which
     * spares the icon cache from being thrown away on every package event.
     */
    /**
     * Serializes refreshes. Package events burst during install
     * sessions, and each load takes a different amount of binder
     * time: without this, an older load can publish after a newer
     * one, resurrecting a removed app (or hiding an added one)
     * until the next package event, and reconciliation then runs
     * against that stale list. The TV ViewModel serializes its
     * rebuilds for the same reason.
     */
    private val refreshMutex = Mutex()

    private var onChanged: ((PackageKey?) -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            markAvailable(packageName, user)
            refresh()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            markAvailable(packageName, user)
            refresh()
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) =
            refresh(PackageKey(packageName, serialFor(user)))

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach { markAvailable(it, user) }
            refresh()
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            // `replacing` means an update is in flight and the package is about to be back,
            // so it is not really unavailable and must not be recorded as such.
            if (!replacing) {
                val serial = serialFor(user)
                _unavailable.value = _unavailable.value + packageNames.map { PackageKey(it, serial) }
            }
            refresh()
        }

        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = refresh()

        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = refresh()

        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle,
        ) = refresh()
    }

    /**
     * Registers the callback and starts loading the initial list. Tied to the Activity lifecycle.
     *
     * The load is asynchronous, so the first frame does not wait for it. Until it arrives [apps] is
     * empty, which every consumer already handles: the drawer shows its loading state and
     * `ReconcileUseCase` refuses to run against an empty list precisely so that it cannot mistake
     * "not loaded yet" for "everything was uninstalled".
     */
    fun start(onChanged: (PackageKey?) -> Unit) {
        this.onChanged = onChanged
        launcherApps.registerCallback(callback)
        refresh()
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
        onChanged = null
    }

    /**
     * Reloads the app list off the main thread, then publishes it.
     *
     * **The load must not be synchronous.** `getActivityList` is a binder call returning every
     * launchable activity on the device, and reading `info.label` for each one opens that app's
     * resources - a few hundred of those is tens of milliseconds at best. This runs from `onStart`,
     * so on the main thread it delayed the first frame after every HOME press, and it runs again for
     * *every* package event, so a Play Store update session stuttered the whole launcher.
     *
     * Launcher3 does the same work on its `LauncherModel` worker thread for the same reason.
     */
    fun refresh(stale: PackageKey? = null) {
        scope.launch {
            refreshMutex.withLock {
                val loaded = withContext(Dispatchers.IO) { loadApps() }
                _apps.value = loaded
                // Back on the caller's dispatcher - consumers update UI state from here.
                onChanged?.invoke(stale)
            }
        }
    }

    private fun loadApps(): List<AppEntry> {
        val profiles = launcherApps.profiles.ifEmpty { listOf(Process.myUserHandle()) }
        val primary = Process.myUserHandle()
        return profiles.flatMap { user ->
            val serial = serialFor(user)
            // Null package means "every package for this user".
            runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList())
                .map { info ->
                    AppEntry(
                        componentName = info.componentName,
                        label = info.label?.toString().orEmpty(),
                        user = user,
                        profileSerial = serial,
                        isWorkProfile = user != primary,
                    )
                }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** Packages present and usable right now, for reconciliation. */
    fun installedKeys(): Set<PackageKey> = _apps.value.map { it.packageKey }.toSet()

    /**
     * Profiles that exist but are currently off (quiet mode), by serial.
     *
     * Read live rather than accumulated from callbacks: a profile that was
     * already quiet when this process started has delivered no
     * `onPackagesUnavailable`, so the callback-built [unavailable] set is
     * empty for it and reconciliation would mistake its apps for uninstalled.
     */
    /** Launchable activities present right now, for reconciliation. */
    fun installedActivityKeys(): Set<ActivityKey> = _apps.value.map {
        ActivityKey(it.componentName.packageName, it.componentName.className, it.profileSerial)
    }.toSet()

    fun unavailableProfileSerials(): Set<Long> = runCatching {
        launcherApps.profiles
            .filter { userManager.isQuietModeEnabled(it) }
            .map { serialFor(it) }
            .toSet()
    }.getOrDefault(emptySet())

    fun icon(entry: AppEntry, densityDpi: Int): Drawable? = runCatching {
        val info = launcherApps.resolveActivity(
            android.content.Intent().setComponent(entry.componentName),
            entry.user,
        )
        // Badged, so a work-profile copy is visibly distinct from the personal one.
        info?.getBadgedIcon(densityDpi)
    }.getOrNull()

    fun icon(componentName: ComponentName, profileSerial: Long, densityDpi: Int): Drawable? {
        val entry = _apps.value.firstOrNull {
            it.componentName == componentName && it.profileSerial == profileSerial
        } ?: return null
        return icon(entry, densityDpi)
    }

    fun entryFor(componentName: ComponentName, profileSerial: Long): AppEntry? =
        _apps.value.firstOrNull {
            it.componentName == componentName && it.profileSerial == profileSerial
        }

    fun userFor(profileSerial: Long): UserHandle =
        userManager.getUserForSerialNumber(profileSerial) ?: Process.myUserHandle()

    /**
     * Launches an activity, animating out of [sourceBounds] with [options].
     *
     * [options] comes from [ActivityBridge.launchAnimationOptions] and carries a clip reveal from the
     * icon's rect, which is as close to the system launcher's app-open transition as an
     * unprivileged app can get; the real shared-element handoff needs SystemUI-signed integration.
     * Null is accepted and means the system's default transition.
     */
    fun launch(entry: AppEntry, sourceBounds: Rect?, options: Bundle? = null): Boolean = runCatching {
        launcherApps.startMainActivity(entry.componentName, entry.user, sourceBounds, options)
        true
    }.getOrDefault(false)

    /**
     * The app's static, dynamic and pinned shortcuts.
     *
     * Empty rather than throwing when we are not the default home app: `getShortcuts` is
     * gated on holding that role, and a long-press menu with no shortcuts in it is a much
     * better outcome than a crash on the home screen.
     */
    @Suppress("DEPRECATION")
    fun shortcuts(packageName: String, user: UserHandle): List<ShortcutInfo> = runCatching {
        val query = LauncherApps.ShortcutQuery()
            .setPackage(packageName)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        launcherApps.getShortcuts(query, user).orEmpty()
    }.getOrDefault(emptyList())

    fun shortcutIcon(shortcut: ShortcutInfo, densityDpi: Int): Drawable? = runCatching {
        launcherApps.getShortcutBadgedIconDrawable(shortcut, densityDpi)
    }.getOrNull()

    fun pinShortcut(shortcut: ShortcutInfo): Boolean = runCatching {
        val pinned = shortcuts(shortcut.`package`, shortcut.userHandle)
            .filter { it.isPinned }
            .map { it.id }
        launcherApps.pinShortcuts(shortcut.`package`, pinned + shortcut.id, shortcut.userHandle)
        true
    }.getOrDefault(false)

    fun startShortcut(shortcut: ShortcutInfo, sourceBounds: Rect?, options: Bundle? = null): Boolean =
        runCatching {
            launcherApps.startShortcut(shortcut, sourceBounds, options)
            true
        }.getOrDefault(false)

    fun startAppDetails(entry: AppEntry, sourceBounds: Rect?): Boolean = runCatching {
        launcherApps.startAppDetailsActivity(entry.componentName, entry.user, sourceBounds, null)
        true
    }.getOrDefault(false)

    private fun markAvailable(packageName: String, user: UserHandle) {
        val key = PackageKey(packageName, serialFor(user))
        if (key in _unavailable.value) _unavailable.value = _unavailable.value - key
    }

    private fun serialFor(user: UserHandle): Long = userManager.getSerialNumberForUser(user)
}
