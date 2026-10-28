package com.vayunmathur.appstore.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CatalogRepository
import com.vayunmathur.appstore.data.DefaultRepos
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
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.util.SourceFilter
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
) : ViewModel() {

    internal val catalog = CatalogRepository(context, db, viewModelScope)
    internal val play = PlayRepository(context)
    internal val accrescent = AccrescentRepository(context, db)
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
    internal val selectedAppFlow = MutableStateFlow<UnifiedApp?>(null)
    internal val isLoadingDetailsFlow = MutableStateFlow(false)
    internal var detailJob: Job? = null

    // --- Browse (one home section's full catalogue) -----------------------------------
    internal val browseSectionFlow = MutableStateFlow("")
    internal val browseTitleFlow = MutableStateFlow("")
    internal val browseAppsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val isLoadingBrowseFlow = MutableStateFlow(false)
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
        selectedAppFlow,
        isLoadingDetailsFlow,
        combine(installedRepo.apps, installedRepo.icons) { installed, icons -> installed to icons },
        installer.stages,
        installer.verification,
    ) { app, loading, (installed, icons), stages, verification ->
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
     * installed versions behind it, so each row can show old → new.
     */
    val updatesUi: StateFlow<TvUpdatesUiState> = combine(
        catalogUpdatesFlow,
        playUpdatesFlow,
        accrescentUpdatesFlow,
        combine(installedRepo.apps, installedRepo.icons, installer.stages) { installed, icons, stages ->
            Triple(installed, icons, stages)
        },
        combine(isCheckingUpdatesFlow, lastUpdateCheckFlow, statusMessageFlow) { checking, checkedAt, message ->
            Triple(checking, checkedAt, message)
        },
    ) { catalogUpdates, playUpdates, accrescentUpdates, (installed, icons, stages), (checking, checkedAt, message) ->
        // The surviving row's source decides which download-and-verify path the update
        // takes, so it has to be the same precedence search and the home rows use.
        // Re-check against what is on the device rather than trusting the lists: the
        // Play half is a snapshot from the last network check.
        val installedVersions = installed.associate { it.packageName to it.versionCode }
        val list = (catalogUpdates + playUpdates + accrescentUpdates)
            .sortedBy { it.source.priority }
            .distinctBy { it.packageName }
            .filter { app ->
                val installedVersion = installedVersions[app.packageName] ?: return@filter false
                app.versionCode > installedVersion
            }
            .sortedBy { it.name.lowercase() }
        TvUpdatesUiState(
            updates = list,
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
        combine(browseSectionFlow, browseTitleFlow) { sectionId, title -> sectionId to title },
        browseAppsFlow,
        isLoadingBrowseFlow,
        combine(installedRepo.apps, installedRepo.icons, installer.stages) { installed, icons, stages ->
            Triple(installed, icons, stages)
        },
    ) { (sectionId, title), apps, loading, (installed, icons, stages) ->
        TvBrowseUiState(
            sectionId = sectionId,
            title = title,
            apps = apps,
            isLoading = loading,
            installedPackages = installed.map { it.packageName }.toSet(),
            installedIcons = icons,
            stages = stages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvBrowseUiState())

    init {
        viewModelScope.launch {
            enabledSourcesFlow.value = settings.readEnabledSources()
            // Drop disabled sources' cached rows before anything reads the catalogue:
            // sync only purges when it runs, so a disabled offline source's rows would
            // otherwise survive every restart underneath the toggle (#845).
            catalog.purgeDisabled(enabledSourcesFlow.value)
            installedRepo.refresh()
            if (AppSource.PLAYSTORE in enabledSourcesFlow.value) play.restore()
            refreshPlayInstalledPackages(enabledSourcesFlow.value)
            syncIfNeverSynced()
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
     * Populate the offline catalogues the first time the TV store is opened.
     *
     * Same contract as the phone's `syncIfNeverSynced`: keyed off the catalogue being
     * empty rather than a first-run flag, so it also recovers a store whose first sync
     * failed or whose data was cleared — and stays quiet on every later launch, when
     * re-fetching full catalogues would be a poor trade for at-most-hours-stale data.
     * Progress reports through the existing status line the browse screen already shows.
     */
    private suspend fun syncIfNeverSynced() {
        val enabled = enabledSourcesFlow.value
        val offlineSources = setOf(
            DefaultRepos.FDROID.source, DefaultRepos.MODERN_APPS.source,
            DefaultRepos.PROPRIETARY.source, DefaultRepos.IZZY.source,
        )
        if (enabled.intersect(offlineSources).isEmpty()) return
        if (catalog.recentlyUpdatedBySource(AppSource.FDROID, 1).isNotEmpty() ||
            catalog.recentlyUpdatedBySource(AppSource.MODERN_APPS, 1).isNotEmpty() ||
            catalog.recentlyUpdatedBySource(AppSource.PROPRIETARY, 1).isNotEmpty() ||
            catalog.recentlyUpdatedBySource(AppSource.IZZY, 1).isNotEmpty()
        ) {
            // The offline gate stays quiet, but Accrescent is network-backed: refresh its
            // allowlist + listings on every cold start that reaches this far, same as the phone.
            loadAccrescent(enabled)
            return
        }
        isLoadingFlow.value = true
        val report = catalog.sync(enabled) { step ->
            statusMessageFlow.value = context.getString(
                when (step) {
                    SyncStep.FDROID -> R.string.sync_step_fdroid
                    SyncStep.IZZY -> R.string.sync_step_izzy
                    SyncStep.MODERN_APPS -> R.string.sync_step_modern_apps
                    SyncStep.PROPRIETARY -> R.string.sync_step_proprietary
                },
            )
        }
        statusMessageFlow.value = ""
        isLoadingFlow.value = false
        AppMessages.show(
            when {
                report.allSkipped -> context.getString(R.string.sync_all_sources_off)
                !report.anyFailed -> context.getString(
                    R.string.sync_done,
                    (report.fdroidCount ?: 0) + (report.modernCount ?: 0) +
                        (report.proprietaryCount ?: 0) + (report.izzyCount ?: 0),
                )
                report.fdroidCount == null && report.modernCount == null &&
                    report.proprietaryCount == null && report.izzyCount == null ->
                    context.getString(R.string.sync_failed_all)
                report.fdroidCount == null -> context.getString(R.string.sync_failed_fdroid)
                report.izzyCount == null && AppSource.IZZY in enabled ->
                    context.getString(R.string.sync_failed_izzy)
                report.proprietaryCount == null ->
                    context.getString(R.string.sync_failed_proprietary)
                else -> context.getString(R.string.sync_failed_modern_apps)
            },
        )
    }

    /**
     * Fill the home rows from the TV-filtered catalogue: one section per source with
     * the phone's own titles and subtitles, capped at two grid rows each.
     *
     * A chosen category replaces the browsing rows, mirroring the phone: the user asked
     * a narrow question and a wall of unrelated sections underneath is just noise.
     */
    fun loadHome() {
        viewModelScope.launch {
            isLoadingFlow.value = true
            val enabled = enabledSourcesFlow.value
            val category = selectedCategoryFlow.value
            if (category != null) {
                val apps = categoryAppsFlow.value.ifEmpty { catalog.allByCategory(category) }
                categoryAppsFlow.value = apps
                sectionsFlow.value = listOf(
                    TvSection(
                        id = "category",
                        title = category,
                        subtitle = context.getString(R.string.section_category_subtitle),
                        apps = apps,
                    )
                )
                isLoadingFlow.value = false
                return@launch
            }
            categoriesFlow.value = catalog.categories()
            val sections = buildList {
                if (AppSource.MODERN_APPS in enabled) {
                    val recent = catalog.recentlyUpdatedBySource(AppSource.MODERN_APPS, ROW_LIMIT)
                    if (recent.isNotEmpty()) {
                        add(
                            TvSection(
                                id = "modern-recent",
                                title = context.getString(R.string.section_modern_apps_recent),
                                subtitle = context.getString(R.string.section_modern_apps_recent_subtitle),
                                source = AppSource.MODERN_APPS,
                                apps = recent,
                            )
                        )
                    } else {
                        catalog.allBySource(AppSource.MODERN_APPS).take(ROW_LIMIT)
                            .takeIf { it.isNotEmpty() }?.let {
                                add(
                                    TvSection(
                                        id = "modern",
                                        title = context.getString(R.string.section_modern_apps),
                                        subtitle = context.getString(R.string.section_modern_apps_subtitle),
                                        source = AppSource.MODERN_APPS,
                                        apps = it,
                                    )
                                )
                            }
                    }
                }
                addAll(offlineRows(enabled, ROW_LIMIT))
                if (AppSource.PLAYSTORE in enabled) {
                    val clusters = play.homeClusters().filter { it.apps.isNotEmpty() }
                        .take(CLUSTER_LIMIT)
                    playFullClustersFlow.value = clusters.associate { "play-${it.title}" to it.apps }
                    clusters.forEach { cluster ->
                        add(
                            TvSection(
                                id = "play-${cluster.title}",
                                title = cluster.title,
                                source = AppSource.PLAYSTORE,
                                apps = cluster.apps.take(ROW_LIMIT),
                            )
                        )
                    }
                    if (clusters.isEmpty()) {
                        // No account, or Play changed its stream shape. A top chart is one
                        // request and still gives the screen something beyond this repo's
                        // own dozen apps. Mirrors the phone's fallback.
                        play.topChart().takeIf { it.isNotEmpty() }?.let { chart ->
                            playFullClustersFlow.value = mapOf("play-top" to chart)
                            add(
                                TvSection(
                                    id = "play-top",
                                    title = context.getString(R.string.section_play_top_charts),
                                    source = AppSource.PLAYSTORE,
                                    apps = chart.take(ROW_LIMIT),
                                )
                            )
                        }
                    }
                }
                if (AppSource.ACCRESCENT in enabled) {
                    accrescentAppsFlow.value.take(ROW_LIMIT).takeIf { it.isNotEmpty() }?.let {
                        add(
                            TvSection(
                                id = "accrescent",
                                title = context.getString(R.string.section_accrescent),
                                subtitle = context.getString(R.string.section_accrescent_subtitle),
                                source = AppSource.ACCRESCENT,
                                apps = it,
                            )
                        )
                    }
                }
            }
            // Same precedence the phone uses: the surviving row's source decides the
            // download-and-verify path, so cross-source duplicates resolve identically.
            sectionsFlow.value = sections.map { section ->
                section.copy(
                    apps = section.apps.sortedBy { it.source.priority }
                        .distinctBy { it.packageName }
                )
            }
            isLoadingFlow.value = false
        }
    }

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
     * Fill the Browse screen with one home section's full catalogue, A–Z.
     * Mirrors the phone's `openBrowse`: the whole offline repo for an offline row,
     * the whole paged catalogue for Accrescent, the uncapped cluster for a Play row.
     */
    fun openBrowse(section: TvSection) {
        browseJob?.cancel()
        browseSectionFlow.value = section.id
        browseTitleFlow.value = section.title
        browseAppsFlow.value = emptyList()
        isLoadingBrowseFlow.value = true
        browseJob = viewModelScope.launch {
            val apps = when {
                section.id == "category" -> catalog.allByCategory(section.title)
                section.source != null && section.source != AppSource.PLAYSTORE &&
                    section.source != AppSource.ACCRESCENT ->
                    catalog.allBySource(section.source)
                section.id == "accrescent" -> accrescent.allListings()
                else -> playFullClustersFlow.value[section.id] ?: section.apps
            }
            browseAppsFlow.value = apps.sortedBy { it.name.lowercase() }
            isLoadingBrowseFlow.value = false
        }
    }

    fun clearBrowse() {
        browseJob?.cancel()
        browseSectionFlow.value = ""
        browseTitleFlow.value = ""
        browseAppsFlow.value = emptyList()
    }

    fun selectApp(app: UnifiedApp) {
        detailJob?.cancel()
        selectedAppFlow.value = app
        detailJob = viewModelScope.launch {
            // The catalogue row wins whenever there is one, even if the user tapped a Play
            // tile for the same package. It is the row an install would actually use — it
            // carries the signer and hash an authenticated index published — so showing
            // the Play listing here would describe a download this store is not going to
            // make. Mirrors the phone's selectApp.
            val cached = catalog.byPackage(app.packageName)
            if (cached != null) {
                selectedAppFlow.value = cached
                return@launch
            }
            // Accrescent listings from the home carousel are shells (no version code, no
            // signer yet); fetch the full listing before the page settles.
            if (app.source == AppSource.ACCRESCENT) {
                isLoadingDetailsFlow.value = true
                val details = accrescent.details(app.packageName)
                if (details != null) selectedAppFlow.value = details
                isLoadingDetailsFlow.value = false
                return@launch
            }
            // Play listings from a cluster are shells: no description, no screenshots, no
            // version code. Fill them in before the page settles.
            if (app.source == AppSource.PLAYSTORE && app.screenshots.isEmpty() &&
                AppSource.PLAYSTORE in enabledSourcesFlow.value
            ) {
                isLoadingDetailsFlow.value = true
                val details = play.details(app.packageName)
                if (details != null) selectedAppFlow.value = details
                isLoadingDetailsFlow.value = false
            }
        }
    }

    fun clearSelection() {
        detailJob?.cancel()
        selectedAppFlow.value = null
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
            settings.setSourceEnabled(source, enabled)
            val next = if (enabled) enabledSourcesFlow.value + source else enabledSourcesFlow.value - source
            enabledSourcesFlow.value = next
            catalog.purgeDisabled(next)
            if (!enabled) forgetSource(source)
            clearBrowse()
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
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Open the Play Store on an app's page, falling back to the web listing URL. */
    fun openInPlayStore(packageName: String) {
        runCatching {
            val market = Intent(
                Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (market.resolveActivity(context.packageManager) != null) {
                context.startActivity(market)
                return
            }
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(PlayStoreLinks.playStoreUrl(packageName)))
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
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
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
        super.onCleared()
    }

    private companion object {
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

class TvStoreViewModelFactory(
    private val context: android.content.Context,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TvStoreViewModel(context.applicationContext as Application, db) as T
}
