package com.vayunmathur.appstore.platform
import com.vayunmathur.appstore.R
import androidx.core.net.toUri
import com.vayunmathur.appstore.ui.tv.TvStoreRoute

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CatalogRepository
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.DownloadCache
import com.vayunmathur.appstore.data.InstalledAppsRepository
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreLinks
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.installer.InstallEvents
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.partitionUpdates
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the TV browse and detail screens draw.
 *
 * The repositories are the phone's own — [CatalogRepository], [PlayRepository],
 * [InstalledAppsRepository], [InstallCoordinator] — reused verbatim, so the TV UI
 * can never drift into its own install path. What is TV-specific is only the state
 * derivation: the catalogue reads already filter to TV-compatible listings (see
 * `filterTvCompatible`), and this ViewModel holds the selected app plus the install
 * stages the detail screen reports.
 *
 * Search/update/browse bodies live in TvStoreOps.kt; this file keeps the flows and
 * the small setters so it stays under the file-length limit.
 */
class TvStoreViewModel(
    internal val context: Application,
    db: AppDatabase,
    // Test seams: offline tests inject repositories whose network calls
    // fail deterministically; production uses the defaults.
    playOverride: PlayRepository? = null,
    accrescentOverride: AccrescentRepository? = null,
) : ViewModel() {

    internal val catalog = CatalogRepository(context, db, viewModelScope)
    internal val play = playOverride ?: PlayRepository(context)
    internal val accrescent = accrescentOverride ?: AccrescentRepository(context, db)
    internal val installedRepo = InstalledAppsRepository(context)
    internal val settings = SettingsRepository(context, viewModelScope)
    internal val installer = InstallCoordinator(context, db, play, accrescent, viewModelScope) {
        ApkCertificates.selfSigners(context)
    }

    val autoInstallUpdates = settings.autoInstallUpdates

    val enabledSourcesFlow = MutableStateFlow(AppSource.DEFAULT_ENABLED)
    internal val sectionsFlow = MutableStateFlow<List<TvSection>>(emptyList())
    internal val categoriesFlow = MutableStateFlow<List<String>>(emptyList())
    internal val selectedCategoryFlow = MutableStateFlow<String?>(null)
    internal val categoryAppsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val isLoadingFlow = MutableStateFlow(true)
    internal val statusMessageFlow = MutableStateFlow("")
    /** The listing loaded for the package the Detail route names; see loadDetail. */
    internal val detailAppFlow = MutableStateFlow<UnifiedApp?>(null)
    internal val isLoadingDetailsFlow = MutableStateFlow(false)
    internal var detailJob: Job? = null

    // --- Browse (one home section's full catalogue) -----------------------------------
    /** Loaded Browse content for the section the Browse route names; see loadBrowse. */
    internal val browseContentFlow = MutableStateFlow(TvBrowseUiState())
    internal var browseJob: Job? = null

    // --- Search hub state -----------------------------------------------------------
    internal val queryFlow = MutableStateFlow("")
    internal val searchResultsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val searchFilterFlow = MutableStateFlow(SourceFilter.ALL)
    internal val isSearchingFlow = MutableStateFlow(false)
    internal val hasSearchedFlow = MutableStateFlow(false)
    /** Play's half of the last search failed (e.g. 429); shown above the list. */
    internal val searchPlayErrorFlow = MutableStateFlow("")
    internal var searchJob: Job? = null

    // --- Updates hub state ----------------------------------------------------------
    internal val catalogUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val playUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val accrescentUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val isCheckingUpdatesFlow = MutableStateFlow(false)
    internal val lastUpdateCheckFlow = MutableStateFlow(0L)
    internal var updateAllJob: Job? = null

    // --- Accrescent home row --------------------------------------------------------
    internal val accrescentAppsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())

    // --- Library (installed apps) state ---------------------------------------------
    internal val libraryFilterFlow = MutableStateFlow(SourceFilter.ALL)
    /** Installed packages Play confirmed it hosts; tells a Play app from a sideload. */
    internal val playInstalledPackagesFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Installed packages Accrescent's signed allowlist vouches for. */
    internal val accrescentPackagesFlow = MutableStateFlow<Set<String>>(emptySet())
    /** Full Play clusters by section id, so Browse can list a whole cluster, not the slice. */
    internal val playFullClustersFlow = MutableStateFlow<Map<String, List<UnifiedApp>>>(emptyMap())

    val detail: StateFlow<TvDetailUiState> = combine(
        combine(
            detailAppFlow,
            isLoadingDetailsFlow,
            combine(installedRepo.apps, installedRepo.icons) { installed, icons -> installed to icons },
        ) { app, loading, installedPair -> Triple(app, loading, installedPair) },
        installer.stages,
        installer.verification,
    ) { appTriple, stages, verification ->
        val (app, loading, installedPair) = appTriple
        val (installed, icons) = installedPair
        val pkg = app?.packageName
        TvDetailUiState(
            app = app,
            installedInfo = installed.find { it.packageName == pkg },
            stage = stages[pkg],
            installedIcon = icons[pkg],
            verification = verification[pkg],
            isLoadingDetails = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvDetailUiState())

    /**
     * Everything the TV search hub draws.
     *
     * Same shape as the phone's `SearchUiState`: the raw two-wave results plus the
     * selected [SourceFilter], so the screen is a pure filter over the ranked merge.
     */
    val search: StateFlow<TvSearchUiState> = combine(
        queryFlow,
        combine(searchResultsFlow, searchFilterFlow) { results, filter ->
            results to filter
        },
        isSearchingFlow,
        hasSearchedFlow,
        combine(searchPlayErrorFlow, installedRepo.apps, installedRepo.icons, installer.stages) {
                error, installed, icons, stages ->
            TvSearchAux(error, installed.map { it.packageName }.toSet(), icons, stages)
        },
    ) { query, (results, filter), searching, searched, aux ->
        TvSearchUiState(
            query = query,
            results = results.filter { filter.source == null || it.source == filter.source },
            filter = filter,
            isSearching = searching,
            hasSearched = searched,
            playError = aux.playError,
            installedPackages = aux.installed,
            installedIcons = aux.icons,
            stages = aux.stages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvSearchUiState())

    /**
     * Everything the TV updates hub draws.
     *
     * Same shape as the phone's `UpdatesUiState`: the merged update list plus the
     * installed versions behind it, so each row can show old → new. The foreign
     * partition mirrors the phone: Update All runs the eligible batch only.
     */
    val updatesUi: StateFlow<TvUpdatesUiState> = combine(
        combine(
            catalogUpdatesFlow,
            playUpdatesFlow,
            accrescentUpdatesFlow,
        ) { catalogUpdates, playUpdates, accrescentUpdates ->
            catalogUpdates + playUpdates + accrescentUpdates
        },
        combine(installedRepo.apps, installedRepo.icons, installer.stages) { installed, icons, stages ->
            Triple(installed, icons, stages)
        },
        combine(isCheckingUpdatesFlow, lastUpdateCheckFlow, statusMessageFlow) { checking, checkedAt, message ->
            Triple(checking, checkedAt, message)
        },
    ) { pending, installedTriple, statusTriple ->
        val (installed, icons, stages) = installedTriple
        val (checking, checkedAt, message) = statusTriple
        val partitioned = partitionUpdates(
            pending = pending,
            installed = installed.associateBy { it.packageName },
            enabled = enabledSourcesFlow.value,
            ownPackage = context.packageName,
        )
        TvUpdatesUiState(
            updates = partitioned.eligible,
            foreignUpdates = partitioned.foreign,
            installedIcons = icons,
            installedInfos = installed.associateBy { it.packageName },
            stages = stages,
            isChecking = checking,
            lastCheckedAt = checkedAt,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvUpdatesUiState())

    // Declared after [updatesUi], which it reads: property initialisers run in order.
    val home: StateFlow<TvHomeUiState> = combine(
        combine(sectionsFlow, categoriesFlow, selectedCategoryFlow) { sections, categories, selected ->
            Triple(sections, categories, selected)
        },
        combine(isLoadingFlow, statusMessageFlow) { loading, message -> loading to message },
        combine(installedRepo.apps, installedRepo.icons, installer.stages) { installed, icons, stages ->
            Triple(installed, icons, stages)
        },
        updatesUi,
    ) { (sections, categories, selected), (loading, message), (installed, icons, stages), updates ->
        TvHomeUiState(
            sections = sections,
            categories = categories,
            selectedCategory = selected,
            updateCount = updates.updates.size,
            updatePackages = updates.updates.map { it.packageName }.toSet(),
            installedPackages = installed.map { it.packageName }.toSet(),
            installedIcons = icons,
            stages = stages,
            isLoading = loading,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvHomeUiState())

    /**
     * Every installed app the store can attribute to a source, in catalogue order.
     *
     * Same attribution as the phone's `library` flow: the offline catalogue first,
     * then Accrescent's allowlist, then Play — but only for packages Play confirmed
     * it hosts, so a sideload is left out rather than mislabelled. Every branch is
     * gated on the source still being enabled (#845).
     */
    val library: StateFlow<TvLibraryUiState> = combine(
        catalog.packageIndex,
        installedRepo.apps,
        installedRepo.icons,
        combine(
            libraryFilterFlow,
            playInstalledPackagesFlow,
            accrescentPackagesFlow,
            enabledSourcesFlow,
        ) { filter, playPackages, accrescentPackages, enabled ->
            TvLibraryInputs(filter, playPackages, accrescentPackages, enabled)
        },
    ) { index, installed, icons, inputs ->
        fun sourceOf(pkg: String): AppSource? {
            index[pkg]?.source?.let { runCatching { AppSource.valueOf(it) }.getOrNull() }
                ?.takeIf { it in inputs.enabled }?.let { return it }
            if (AppSource.ACCRESCENT in inputs.enabled && pkg in inputs.accrescentPackages) {
                return AppSource.ACCRESCENT
            }
            if (AppSource.PLAYSTORE in inputs.enabled && pkg in inputs.playPackages) {
                return AppSource.PLAYSTORE
            }
            return null
        }
        val all = installed.mapNotNull { info ->
            sourceOf(info.packageName)?.let { info.toTvListing(it) }
        }
        TvLibraryUiState(
            apps = all.filter { inputs.filter.source == null || it.source == inputs.filter.source },
            filter = inputs.filter,
            counts = SourceFilter.entries.associateWith { f ->
                if (f.source == null) all.size else all.count { it.source == f.source }
            },
            installedIcons = icons,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvLibraryUiState())

    /** One home section's full catalogue, A–Z. Same shape as the phone's `BrowseUiState`. */
    val browse: StateFlow<TvBrowseUiState> = combine(
        browseContentFlow,
        combine(installedRepo.apps, installedRepo.icons, installer.stages) { installed, icons, stages ->
            Triple(installed, icons, stages)
        },
    ) { content, (installed, icons, stages) ->
        content.copy(
            installedPackages = installed.map { it.packageName }.toSet(),
            installedIcons = icons,
            stages = stages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvBrowseUiState())

    init {
        viewModelScope.launch {
            // Crash-orphaned APKs first: a dead install attempt's staged files are
            // garbage, and the 24h age gate keeps this from racing anything
            // this process started (#871).
            withContext(Dispatchers.IO) {
                DownloadCache.sweepStale(context.cacheDir)
            }
            enabledSourcesFlow.value = settings.readEnabledSources()
            // Drop disabled sources' cached rows before anything reads the catalogue:
            // sync only purges when it runs, so a disabled offline source's rows would
            // otherwise survive every restart underneath the toggle (#845).
            catalog.purgeDisabled(enabledSourcesFlow.value)
            installedRepo.refresh()
            if (AppSource.PLAYSTORE in enabledSourcesFlow.value) play.restore()
            refreshPlayInstalledPackages(enabledSourcesFlow.value)
            syncIfNeverSyncedImpl()
            categoriesFlow.value = catalog.categories()
            loadHome()
        }
        viewModelScope.launch { loadAccrescent(enabledSourcesFlow.value) }
        viewModelScope.launch {
            combine(catalog.packageIndex, installedRepo.updatable) { _, _ -> Unit }
                .collect {
                    loadHome()
                    catalogUpdatesFlow.value = catalog.updatesFor(installedRepo.updatable.value)
                }
        }
        viewModelScope.launch {
            // The OS install finishes asynchronously, well after commit for a large app.
            // Re-read the device when it lands so tiles switch to Installed/Open, and
            // re-ask Play which installed packages it hosts, instead of waiting for the
            // next cold start. Mirrors the phone's InstallEvents collector.
            InstallEvents.results.collect { result ->
                if (result.success) {
                    installedRepo.refresh()
                    refreshPlayInstalledPackages()
                }
            }
        }
    }

    /**
     * Fill the home rows from the TV-filtered catalogue: one section per source with
     * the phone's own titles and subtitles, capped at two grid rows each.
     *
     * A chosen category replaces the browsing rows, mirroring the phone: the user asked
     * a narrow question and a wall of unrelated sections underneath is just noise.
     */
    fun loadHome() = loadHomeImpl()

    /** A chosen category replaces the browsing rows; null restores them. Mirrors the phone. */
    fun selectCategory(category: String?) {
        selectedCategoryFlow.value = category
        if (category == null) {
            categoryAppsFlow.value = emptyList()
            loadHome()
            return
        }
        viewModelScope.launch {
            isLoadingFlow.value = true
            categoryAppsFlow.value = catalog.allByCategory(category)
            sectionsFlow.value = listOf(
                TvSection(
                    id = "category",
                    title = category,
                    subtitle = context.getString(R.string.section_category_subtitle),
                    apps = categoryAppsFlow.value,
                )
            )
            isLoadingFlow.value = false
        }
    }

    /**
     * Fill the Browse screen with the full catalogue behind [sectionId] —
     * the argument of `TvStoreRoute.Browse`. Mirrors the phone's loadBrowse:
     * the whole offline repo for an offline row, the whole paged catalogue
     * for Accrescent, the uncapped cluster for a Play row.
     *
     * The section is looked up in the home state that produced it (title,
     * source and capped apps), so the entry binds only to its route
     * argument: a restored Browse entry re-resolves the section it names
     * instead of reading whatever section a ViewModel flow last held.
     */
    fun loadBrowse(sectionId: String) {
        browseJob?.cancel()
        val section = home.value.sections.find { it.id == sectionId }
            ?: sectionsFlow.value.find { it.id == sectionId }
        val title = section?.title ?: browseFallbackTitle(sectionId)
        browseContentFlow.value = TvBrowseUiState(
            sectionId = sectionId,
            title = title,
            apps = emptyList(),
            isLoading = true,
        )
        browseJob = viewModelScope.launch {
            val apps = browseApps(section, sectionId)
            browseContentFlow.value = TvBrowseUiState(
                sectionId = sectionId,
                title = title,
                apps = apps.sortedBy { it.name.lowercase() },
                isLoading = false,
            )
        }
    }

    /** The full catalogue behind one Browse section; see [loadBrowse]. */
    private suspend fun browseApps(section: TvSection?, sectionId: String): List<UnifiedApp> {
        if (sectionId == "category") {
            return section?.let { catalog.allByCategory(it.title).ifEmpty { it.apps } } ?: emptyList()
        }
        val source = section?.source
        if (source != null && source != AppSource.PLAYSTORE && source != AppSource.ACCRESCENT) {
            return catalog.allBySource(source)
        }
        return browseAppsBySectionId(section, sectionId)
    }

    /** The catalogue behind a section named by id, when not by category/source. */
    private suspend fun browseAppsBySectionId(section: TvSection?, sectionId: String): List<UnifiedApp> =
        when (sectionId) {
            "modern", "modern-recent" -> catalog.allBySource(AppSource.MODERN_APPS)
            "proprietary-recent" -> catalog.allBySource(AppSource.PROPRIETARY)
            "fdroid-recent" -> catalog.allBySource(AppSource.FDROID)
            "izzy-recent" -> catalog.allBySource(AppSource.IZZY)
            "accrescent" -> accrescent.allListings()
            else -> playFullClustersFlow.value[sectionId] ?: section?.apps ?: emptyList()
        }

    /** Title for a known section id when home has not (re)built its row yet. */
    private fun browseFallbackTitle(sectionId: String): String = when (sectionId) {
        "modern", "modern-recent" -> context.getString(R.string.section_modern_apps_recent)
        "proprietary-recent" -> context.getString(R.string.section_proprietary_recent)
        "fdroid-recent" -> context.getString(R.string.section_fdroid_recent)
        "izzy-recent" -> context.getString(R.string.section_izzy_recent)
        "accrescent" -> context.getString(R.string.section_accrescent)
        "play-top" -> context.getString(R.string.section_play_top_charts)
        else -> sectionId.removePrefix("play-")
    }

    /**
     * Load the detail page for [packageName] — the argument of
     * `TvStoreRoute.Detail`. The route, not a ViewModel selection, decides
     * which app is on screen, and the previous listing is dropped
     * synchronously so a new destination never renders the last one's app.
     *
     * Resolution mirrors the phone's loadDetail: the catalogue row wins
     * whenever there is one (it carries the signer and hash an install would
     * use), then Accrescent's allowlist, then Play, with a Play shell for a
     * package none of them knows.
     */
    fun loadDetail(packageName: String) {
        detailJob?.cancel()
        detailAppFlow.value = null
        isLoadingDetailsFlow.value = true
        detailJob = viewModelScope.launch {
            val cached = catalog.byPackage(packageName)
            if (cached != null) {
                detailAppFlow.value = cached
                isLoadingDetailsFlow.value = false
                return@launch
            }
            if (AppSource.ACCRESCENT in enabledSourcesFlow.value &&
                packageName in accrescentPackagesFlow.value
            ) {
                val details = accrescent.details(packageName)
                detailAppFlow.value = details ?: UnifiedApp(
                    packageName = packageName,
                    source = AppSource.ACCRESCENT,
                    name = packageName.substringAfterLast('.'),
                )
                isLoadingDetailsFlow.value = false
                return@launch
            }
            detailAppFlow.value = UnifiedApp(
                packageName = packageName,
                source = AppSource.PLAYSTORE,
                name = packageName.substringAfterLast('.'),
            )
            if (AppSource.PLAYSTORE in enabledSourcesFlow.value) {
                val details = play.details(packageName)
                if (details != null) detailAppFlow.value = details
            }
            isLoadingDetailsFlow.value = false
        }
    }

    // --- Search hub: body in TvStoreOps.kt -------------------------------------------------
    fun setSearch(query: String) = setSearchImpl(query)

    fun setSearchFilter(filter: SourceFilter) {
        searchFilterFlow.value = filter
    }

    // --- Updates hub: bodies in TvStoreOps.kt ----------------------------------------------
    fun checkForUpdates() = checkForUpdatesImpl()

    fun updateAll() = updateAllImpl()


    // --- Sources tab ------------------------------------------------------------------
    /**
     * Turn a source on or off, and make the rest of the store agree immediately.
     * Mirrors the phone's `setSourceEnabled`: disabling drops the source's cached
     * rows and whatever it had already contributed to the screens.
     */
    fun setSourceEnabled(source: AppSource, enabled: Boolean) {
        viewModelScope.launch {
            // In-memory state first: the screens (and anything observing the
            // flows) agree with the toggle as soon as this coroutine runs,
            // instead of after the DataStore write and cache purge below.
            val next = if (enabled) enabledSourcesFlow.value + source else enabledSourcesFlow.value - source
            enabledSourcesFlow.value = next
            if (!enabled) forgetSource(source)
            settings.setSourceEnabled(source, enabled)
            catalog.purgeDisabled(next)
            categoriesFlow.value = catalog.categories()
            loadHome()
            loadAccrescent(next)
        }
    }

    fun setAutoInstallUpdates(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoInstallUpdates(enabled) }
    }


    /** Drop what a source had already contributed to the screens. Mirrors the phone. */
    private fun forgetSource(source: AppSource) {
        when (source) {
            AppSource.PLAYSTORE -> {
                sectionsFlow.value = sectionsFlow.value.filter { it.source != AppSource.PLAYSTORE }
                playFullClustersFlow.value = emptyMap()
                playUpdatesFlow.value = emptyList()
                playInstalledPackagesFlow.value = emptySet()
            }
            AppSource.ACCRESCENT -> {
                accrescentAppsFlow.value = emptyList()
                accrescentPackagesFlow.value = emptySet()
                accrescentUpdatesFlow.value = emptyList()
            }
            // The offline sources have no in-memory rows of their own: browse, search and the
            // update check all read the Room cache that purgeDisabled just emptied.
            else -> Unit
        }
    }

    fun install(app: UnifiedApp) {
        viewModelScope.launch {
            val outcome = installer.install(app)
            val verdict = outcome.verification
            AppMessages.show(
                when (verdict) {
                    is VerificationResult.Rejected ->
                        context.getString(
                            R.string.install_blocked,
                            app.name,
                            verdict.reason,
                        )
                    else ->
                        if (outcome.started) context.getString(
                            R.string.install_started,
                            app.name,
                        )
                        else context.getString(
                            R.string.install_failed,
                            app.name,
                        )
                },
            )
            if (outcome.started) installedRepo.refresh()
        }
    }

    fun openApp(packageName: String) {
        val pm = context.packageManager
        val leanbackIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            setPackage(packageName)
        }
        val intent = if (leanbackIntent.resolveActivity(pm) != null) leanbackIntent
            else runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull() ?: return
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Open a link in the browser, e.g. an app's website or source code. */
    fun openInBrowser(url: String) {
        if (url.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Open the Play Store on an app's page, falling back to the web listing URL. */
    fun openInPlayStore(packageName: String) {
        runCatching {
            val market = Intent(
                Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (market.resolveActivity(context.packageManager) != null) {
                context.startActivity(market)
                return
            }
            context.startActivity(
                Intent(Intent.ACTION_VIEW, PlayStoreLinks.playStoreUrl(packageName).toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Uninstall via the system dialog. Mirrors the phone's uninstall: the store never
     * uninstalls silently, and a missing uninstaller surfaces once via AppMessages.
     */
    fun uninstallApp(packageName: String) {
        val started = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, "package:$packageName".toUri())
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (!started) AppMessages.show(context.getString(R.string.uninstaller_unavailable))
    }

    /** Clear a failed install's stage so the detail screen offers install again. */
    fun dismissInstallFailure(packageName: String) =
        installer.dismissFailure(packageName)

    override fun onCleared() {
        accrescent.shutdown()
    }

    internal companion object {
        /** Two grid rows per home section: the phone's rows become dense grids, not walls. */
        const val ROW_LIMIT = 6
        const val CLUSTER_LIMIT = 4
    }
}

/** Small holder so the search combine's aux triple stays readable. */
private data class TvSearchAux(
    val playError: String,
    val installed: Set<String>,
    val icons: Map<String, android.graphics.drawable.Drawable>,
    val stages: Map<String, InstallStage>,
)

/** Factory creating TvStoreViewModel with its context and database. */
class TvStoreViewModelFactory(
    private val context: android.content.Context,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TvStoreViewModel(context.applicationContext as Application, db) as T
}
