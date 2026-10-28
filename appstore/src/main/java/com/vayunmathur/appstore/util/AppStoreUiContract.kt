package com.vayunmathur.appstore.util

import android.graphics.drawable.Drawable
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult

/**
 * The UI contract between [AppStoreViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — see `src/screenshotTest`, which is where the
 * store listing images come from. It lives in `util` rather than `ui` so the dependency
 * runs one way: `ui` depends on `util`, and the ViewModel implements these interfaces.
 *
 * Section titles are plain strings rather than string resources because some of them come
 * from Play at runtime ("Recommended for you", "New releases") and the rest are resolved
 * by the ViewModel, which holds a Context. A preview supplies literals.
 */

/** How a row of apps is drawn. */
enum class SectionLayout {
    /** Horizontally scrolling tiles — a browsing row. */
    CAROUSEL,

    /** Vertical rows with summaries — a list you read. */
    LIST,
}

/** A titled group of apps on the home screen. */
data class AppSection(
    val id: String,
    val title: String,
    val apps: List<UnifiedApp>,
    val layout: SectionLayout = SectionLayout.CAROUSEL,
    /** Optional one-line explanation under the title. */
    val subtitle: String? = null,
)

/** Source filter, shared by search and the library. Ordering here implies no ranking. */
enum class SourceFilter(val source: AppSource?) {
    ALL(null),
    MODERN_APPS(AppSource.MODERN_APPS),
    PROPRIETARY(AppSource.PROPRIETARY),
    FDROID(AppSource.FDROID),
    IZZY(AppSource.IZZY),
    PLAYSTORE(AppSource.PLAYSTORE),
    ACCRESCENT(AppSource.ACCRESCENT),
}

/** Everything the home screen draws. */
data class HomeUiState(
    val sections: List<AppSection> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val updateCount: Int = 0,
    val installedPackages: Set<String> = emptySet(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    /** Transient status line, e.g. what the sync is currently fetching. */
    val statusMessage: String = "",
)

/** Everything the browse screen draws: one home section's full catalogue. */
data class BrowseUiState(
    val sectionId: String = "",
    val title: String = "",
    val apps: List<UnifiedApp> = emptyList(),
    val isLoading: Boolean = false,
    val installedPackages: Set<String> = emptySet(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
)

/** Everything the search screen draws. */
data class SearchUiState(
    val query: String = "",
    val results: List<UnifiedApp> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val isSearching: Boolean = false,
    /** True once a search has run for [query], so "no results" can be told from "not yet". */
    val hasSearched: Boolean = false,
    /**
     * Play's half failed (e.g. rate-limited, issue #797) while the offline half answered.
     * Blank when Play answered or was never asked, so the list alone is the whole story.
     */
    val playError: String = "",
    val installedPackages: Set<String> = emptySet(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
)

/** Everything the app detail screen draws. [app] is null until one has been selected. */
data class AppDetailUiState(
    val app: UnifiedApp? = null,
    /** The installed copy of [app], or null when the package isn't installed. */
    val installedInfo: InstalledInfo? = null,
    /** Verdict of the last install attempt for this package. */
    val verification: VerificationResult? = null,
    val stage: InstallStage? = null,
    val installedIcon: Drawable? = null,
    /** True while richer details are being fetched over the network. */
    val isLoadingDetails: Boolean = false,
) {
    val isInstalled: Boolean get() = installedInfo != null

    val hasUpdate: Boolean
        get() = installedInfo != null && (app?.versionCode ?: 0L) > installedInfo.versionCode
}

/** Everything the updates screen draws. */
data class UpdatesUiState(
    val updates: List<UnifiedApp> = emptyList(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    /** Currently-installed info per package, so the row can show old → new versions. */
    val installedInfos: Map<String, InstalledInfo> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
    val isChecking: Boolean = false,
    /** ms since epoch of the last completed check, 0 if never. */
    val lastCheckedAt: Long = 0L,
    val statusMessage: String = "",
)

/** Everything the library (installed apps) screen draws. */
data class LibraryUiState(
    val apps: List<UnifiedApp> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val counts: Map<SourceFilter, Int> = emptyMap(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
)

/**
 * Callbacks shared by every screen that can show an app.
 *
 * Every method has a no-op default so a preview can render a screen without supplying
 * behaviour — [Noop] is the whole implementation a preview needs.
 */
interface AppActions {
    fun install(app: UnifiedApp) {}
    fun openApp(packageName: String) {}

    companion object {
        val Noop: AppActions = object : AppActions {}
    }
}

/** Home-screen callbacks. */
interface HomeActions : AppActions {
    fun selectCategory(category: String?) {}
    fun refresh() {}

    companion object {
        val Noop: HomeActions = object : HomeActions {}
    }
}

/** Search-screen callbacks. */
interface SearchActions : AppActions {
    fun setSearch(query: String) {}
    fun setSearchFilter(filter: SourceFilter) {}

    companion object {
        val Noop: SearchActions = object : SearchActions {}
    }
}

/** Detail-screen callbacks. */
interface AppDetailActions : AppActions {
    fun uninstallApp(packageName: String) {}
    fun openInPlayStore(packageName: String) {}
    fun openInBrowser(url: String) {}
    fun shareApp(app: UnifiedApp) {}
    fun dismissInstallFailure(packageName: String) {}

    companion object {
        val Noop: AppDetailActions = object : AppDetailActions {}
    }
}

/** Updates-screen callbacks. */
interface UpdatesActions : AppActions {
    fun checkForUpdates() {}
    fun updateAll() {}

    companion object {
        val Noop: UpdatesActions = object : UpdatesActions {}
    }
}

/** Library-screen callbacks. */
interface LibraryActions : AppActions {
    fun setLibraryFilter(filter: SourceFilter) {}

    companion object {
        val Noop: LibraryActions = object : LibraryActions {}
    }
}
