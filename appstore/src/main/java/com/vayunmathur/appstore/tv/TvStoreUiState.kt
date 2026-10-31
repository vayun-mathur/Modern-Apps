package com.vayunmathur.appstore.tv

import android.graphics.drawable.Drawable
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.SourceFilter

/**
 * One titled row on the TV home screen. Same shape as the phone's `AppSection`:
 * the title and subtitle are the phone's own section strings, so the TV rows read
 * exactly like the phone's.
 */
data class TvSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val source: AppSource? = null,
    val apps: List<UnifiedApp> = emptyList(),
)

/** Everything the TV home screen draws. Same shape as the phone's `HomeUiState`. */
data class TvHomeUiState(
    val sections: List<TvSection> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val updateCount: Int = 0,
    /** Packages with a pending update, so cards can badge Update over Installed. */
    val updatePackages: Set<String> = emptySet(),
    val installedPackages: Set<String> = emptySet(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
    val isLoading: Boolean = false,
    val statusMessage: String = "",
)

/** Everything the TV browse screen draws. Same shape as the phone's `BrowseUiState`. */
data class TvBrowseUiState(
    val sectionId: String = "",
    val title: String = "",
    val apps: List<UnifiedApp> = emptyList(),
    val isLoading: Boolean = false,
    val installedPackages: Set<String> = emptySet(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
)

/** Everything the TV library (installed apps) screen draws. */
data class TvLibraryUiState(
    val apps: List<UnifiedApp> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val counts: Map<SourceFilter, Int> = emptyMap(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
)

internal data class TvLibraryInputs(
    val filter: SourceFilter = SourceFilter.ALL,
    val playPackages: Set<String> = emptySet(),
    val accrescentPackages: Set<String> = emptySet(),
    val enabled: Set<AppSource> = emptySet(),
)

/** Everything the TV detail screen draws. */
data class TvDetailUiState(
    val app: UnifiedApp? = null,
    val installedInfo: InstalledInfo? = null,
    val stage: InstallStage? = null,
    val installedIcon: Drawable? = null,
    val verification: VerificationResult? = null,
    /** True while richer Play/Accrescent details are being fetched over the network. */
    val isLoadingDetails: Boolean = false,
) {
    val isInstalled: Boolean get() = installedInfo != null

    val hasUpdate: Boolean
        get() = installedInfo != null && (app?.versionCode ?: 0L) > installedInfo.versionCode
}

/** Everything the TV search hub draws. Same shape as the phone's `SearchUiState`. */
data class TvSearchUiState(
    val query: String = "",
    val results: List<UnifiedApp> = emptyList(),
    val filter: SourceFilter = SourceFilter.ALL,
    val isSearching: Boolean = false,
    /** True once a search has run for [query], so "no results" can be told from "not yet". */
    val hasSearched: Boolean = false,
    /**
     * Play's half failed (e.g. rate-limited) while the offline half answered. Blank when
     * Play answered or was never asked, so the list alone is the whole story.
     */
    val playError: String = "",
    val installedPackages: Set<String> = emptySet(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
)

/** Everything the TV updates hub draws. Same shape as the phone's `UpdatesUiState`. */
data class TvUpdatesUiState(
    val updates: List<UnifiedApp> = emptyList(),
    /** Newer versions of packages another store installed: listed, never bulk-run. */
    val foreignUpdates: List<UnifiedApp> = emptyList(),
    val installedIcons: Map<String, Drawable> = emptyMap(),
    /** Currently-installed info per package, so the row can show old → new versions. */
    val installedInfos: Map<String, InstalledInfo> = emptyMap(),
    val stages: Map<String, InstallStage> = emptyMap(),
    val isChecking: Boolean = false,
    /** ms since epoch of the last completed check, 0 if never. */
    val lastCheckedAt: Long = 0L,
    val statusMessage: String = "",
)
