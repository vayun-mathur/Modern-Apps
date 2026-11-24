package com.vayunmathur.launcher.wear.domain

import kotlinx.serialization.Serializable

/** One launchable watch app (icon is resolved on-device, never synced). */
data class WatchApp(
    val packageName: String,
    val className: String,
    val label: String,
)

/** One watch-local launch, for the recents row. */
@Serializable
data class RecentLaunch(
    val packageName: String,
    val launchedAtMillis: Long,
)

/** Our own wear companions, in quick-access order. Presence is checked, never assumed. */
data class WearCompanion(val packageName: String, val label: String)

val WEAR_COMPANIONS: List<WearCompanion> = listOf(
    WearCompanion("com.vayunmathur.appstore", "App Store"),
    WearCompanion("com.vayunmathur.calendar", "Calendar"),
    WearCompanion("com.vayunmathur.contacts", "Contacts"),
    WearCompanion("com.vayunmathur.notes", "Notes"),
    WearCompanion("com.vayunmathur.weather", "Weather"),
    WearCompanion("com.vayunmathur.clock", "Clock"),
)

const val MAX_RECENTS = 6

/** The watch home's sections, all derived from installed apps + synced favorites. */
data class WearLauncherSections(
    /** Synced phone favorites that are installed on the watch, in phone rank order. */
    val favorites: List<WatchApp> = emptyList(),
    /** Synced favorites with no matching watch app (named so absence is honest). */
    val missingFavorites: List<WearFavorite> = emptyList(),
    val recents: List<WatchApp> = emptyList(),
    val companions: List<WatchApp> = emptyList(),
    val all: List<WatchApp> = emptyList(),
)

/**
 * Maps installed watch apps into home sections. Pure: installed-app
 * enumeration and icon loading stay in `platform/`.
 *
 * - Duplicate packages keep the first label-sorted component.
 * - The launcher itself ([ownPackageName]) is excluded: it is the surface.
 * - A favorite matches by package, preferring its exact component.
 * - Recents exclude favorites (a pinned app needs no second row) and cap
 *   at [MAX_RECENTS]; companions exclude favorites only, so a recently
 *   used companion can appear twice by design (quick access is a shortcut
 *   row, not a partition of all apps).
 */
fun buildWearLauncherSections(
    installed: List<WatchApp>,
    snapshot: WearFavoritesSnapshot,
    recents: List<RecentLaunch>,
    ownPackageName: String,
): WearLauncherSections {
    val sorted = installed
        .filter { it.packageName.isNotBlank() && it.className.isNotBlank() }
        .filter { it.packageName != ownPackageName }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }, { it.className }))
    // The drawer shows one row per package; favorites may name any component.
    val apps = sorted.distinctBy { it.packageName }
    if (apps.isEmpty()) {
        return WearLauncherSections(missingFavorites = snapshot.favorites)
    }
    val byPackage = apps.associateBy { it.packageName }
    val favorites = mutableListOf<WatchApp>()
    val missing = mutableListOf<WearFavorite>()
    snapshot.favorites.sortedBy { it.rank }.forEach { favorite ->
        val exact = sorted.firstOrNull {
            it.packageName == favorite.packageName && it.className == favorite.className
        }
        val match = exact ?: byPackage[favorite.packageName]
        if (match == null) missing += favorite else favorites += match
    }
    val favoritePackages = favorites.map { it.packageName }.toSet()
    val recentApps = recents
        .sortedByDescending { it.launchedAtMillis }
        .mapNotNull { byPackage[it.packageName] }
        .distinctBy { it.packageName }
        .filterNot { it.packageName in favoritePackages }
        .take(MAX_RECENTS)
    val companions = WEAR_COMPANIONS
        .filterNot { it.packageName in favoritePackages }
        .mapNotNull { byPackage[it.packageName] }
    return WearLauncherSections(favorites, missing, recentApps, companions, apps)
}

/** Phone-parity drawer filter: label prefix matches first, then contains. */
fun filterWatchApps(apps: List<WatchApp>, query: String): List<WatchApp> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return apps
    val (prefix, contains) = apps
        .filter { it.label.contains(trimmed, ignoreCase = true) }
        .partition { it.label.startsWith(trimmed, ignoreCase = true) }
    return prefix + contains
}

/** Records a launch at the front of the recents list (watch-local only). */
fun recordRecentLaunch(
    existing: List<RecentLaunch>,
    packageName: String,
    launchedAtMillis: Long,
): List<RecentLaunch> {
    if (packageName.isBlank()) return existing
    return (listOf(RecentLaunch(packageName, launchedAtMillis)) + existing)
        .distinctBy { it.packageName }
        .sortedByDescending { it.launchedAtMillis }
        .take(MAX_RECENTS)
}

/**
 * A settings row the watch may offer. [action] is the platform intent
 * action string; a row exists only when the platform resolves it, so the
 * launcher never renders a fake brightness/sound toggle it cannot honour.
 * Brightness in particular has no public direct intent on Wear OS: the
 * Display row opens the system display settings where brightness lives.
 */
enum class WearSettingsTarget(val label: String, val action: String) {
    DISPLAY("Display & brightness", "android.settings.DISPLAY_SETTINGS"),
    SOUND("Sound", "android.settings.SOUND_SETTINGS"),
    CONNECTIVITY("Connectivity", "android.settings.WIFI_SETTINGS"),
    ACCESSIBILITY("Accessibility", "android.settings.ACCESSIBILITY_SETTINGS"),
    APPS("App info", "android.settings.APPLICATION_SETTINGS"),
    SYSTEM("System settings", "android.settings.SETTINGS"),
}

/** Only the settings rows whose intent actually resolves on this watch. */
fun availableWearSettingsTargets(resolvableActions: Set<String>): List<WearSettingsTarget> =
    WearSettingsTarget.entries.filter { it.action in resolvableActions }
