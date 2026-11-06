package com.vayunmathur.appstore.util

import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.VerificationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Screen-state derivations: which rows read as installed, when the update badge
 * shows, and how the search filter narrows the list.
 */
class UiContractStateTest {

    private fun app(pkg: String = "com.example.app", versionCode: Long = 2L) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = "Example",
        versionCode = versionCode,
    )

    private fun installed(pkg: String = "com.example.app", versionCode: Long = 1L) = InstalledInfo(
        packageName = pkg,
        name = "Example",
        versionName = "1.0",
        versionCode = versionCode,
    )

    // --- AppDetailUiState ---

    @Test
    fun `detail reads installed only with installed info`() {
        assertFalse(AppDetailUiState(app = app()).isInstalled)
        assertTrue(AppDetailUiState(app = app(), installedInfo = installed()).isInstalled)
    }

    @Test
    fun `detail update badge needs a newer catalogue version`() {
        assertTrue(AppDetailUiState(app = app(2L), installedInfo = installed(1L)).hasUpdate)
        assertFalse(AppDetailUiState(app = app(1L), installedInfo = installed(1L)).hasUpdate)
        assertFalse(AppDetailUiState(app = app(1L), installedInfo = installed(2L)).hasUpdate)
        assertFalse(AppDetailUiState(app = null, installedInfo = installed()).hasUpdate)
        assertFalse(AppDetailUiState(app = app(), installedInfo = null).hasUpdate)
    }

    @Test
    fun `detail carries the verification verdict and stage`() {
        val verdict = VerificationResult.Verified("hash matches for 1 of 1 file(s)")
        val state = AppDetailUiState(app = app(), verification = verdict, isLoadingDetails = true)

        assertEquals(verdict, state.verification)
        assertTrue(state.isLoadingDetails)
    }

    // --- SearchUiState filtering ---

    @Test
    fun `every source filter value maps to its source`() {
        assertEquals(null, SourceFilter.ALL.source)
        assertEquals(AppSource.MODERN_APPS, SourceFilter.MODERN_APPS.source)
        assertEquals(AppSource.PROPRIETARY, SourceFilter.PROPRIETARY.source)
        assertEquals(AppSource.FDROID, SourceFilter.FDROID.source)
        assertEquals(AppSource.IZZY, SourceFilter.IZZY.source)
        assertEquals(AppSource.PLAYSTORE, SourceFilter.PLAYSTORE.source)
        assertEquals(AppSource.ACCRESCENT, SourceFilter.ACCRESCENT.source)
    }

    // --- UpdatesUiState / LibraryUiState shapes ---

    @Test
    fun `updates state separates eligible from foreign by construction`() {
        val eligible = app("com.example.a")
        val foreign = app("com.example.b")
        val state = UpdatesUiState(
            updates = listOf(eligible),
            foreignUpdates = listOf(foreign),
            installedInfos = mapOf("com.example.a" to installed("com.example.a")),
        )

        assertEquals(listOf(eligible), state.updates)
        assertEquals(listOf(foreign), state.foreignUpdates)
        assertEquals(1, state.installedInfos.size)
    }

    @Test
    fun `library state counts per filter`() {
        val state = LibraryUiState(
            apps = listOf(
                app("com.example.a").copy(source = AppSource.FDROID),
                app("com.example.b").copy(source = AppSource.PLAYSTORE),
            ),
            filter = SourceFilter.ALL,
            counts = mapOf(SourceFilter.ALL to 2, SourceFilter.FDROID to 1),
        )

        assertEquals(2, state.apps.size)
        assertEquals(2, state.counts[SourceFilter.ALL])
        assertEquals(1, state.counts[SourceFilter.FDROID])
    }

    @Test
    fun `home state defaults to an empty idle screen`() {
        val state = HomeUiState()

        assertTrue(state.sections.isEmpty())
        assertTrue(state.categories.isEmpty())
        assertEquals(0, state.updateCount)
        assertFalse(state.isLoading)
        assertFalse(state.isSyncing)
        assertEquals("", state.statusMessage)
    }

    @Test
    fun `section layout defaults to carousel`() {
        assertEquals(SectionLayout.CAROUSEL, AppSection("id", "title", emptyList()).layout)
    }

    // --- Noop actions ---

    @Test
    fun `noop actions accept every call without effect`() {
        AppActions.Noop.install(app())
        AppActions.Noop.openApp("com.example.app")
        HomeActions.Noop.selectCategory("Games")
        HomeActions.Noop.refresh()
        SearchActions.Noop.setSearch("query")
        SearchActions.Noop.setSearchFilter(SourceFilter.FDROID)
        AppDetailActions.Noop.uninstallApp("com.example.app")
        AppDetailActions.Noop.openInPlayStore("com.example.app")
        AppDetailActions.Noop.openInBrowser("https://example.org")
        AppDetailActions.Noop.shareApp(app())
        AppDetailActions.Noop.dismissInstallFailure("com.example.app")
        UpdatesActions.Noop.checkForUpdates()
        UpdatesActions.Noop.updateAll()
        LibraryActions.Noop.setLibraryFilter(SourceFilter.PLAYSTORE)
    }
}
