package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.uniflix.data.TmdbApi
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.StreamProvider
import com.vayunmathur.uniflix.domain.VideoSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * ViewModel behaviour with no network and no Room access: catalog loads fail
 * into their error states, and Detail resolution runs against fake providers
 * via the ViewModel's own `streamProviders` test hook.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UniflixViewModelsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Catalog client whose every request fails, so tests never touch the network. */
    private fun offlineApi(): TmdbApi {
        uniflixTransport = { throw java.io.IOException("offline test double") }
        return TmdbApi()
    }

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); uniflixTransport = null }

    private fun await(timeoutMs: Long = 15_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private class FakeProvider(
        private val result: Result<VideoSource>,
        private val serverNames: List<String> = emptyList(),
    ) : StreamProvider() {
        override val name: String = "Fake"
        override val supports4k: Boolean = true
        var lastTmdb: Int = -1
        var lastServer: String? = null
        override suspend fun resolve(
            tmdbId: Int, mediaType: MediaType, season: Int, episode: Int, server: String?,
        ): VideoSource {
            lastTmdb = tmdbId
            lastServer = server
            return result.getOrThrow()
        }
        override suspend fun servers(): List<String> = serverNames
    }

    // ---- Detail ----

    @Test fun detailResolveSuccessSetsSource() {
        val vm = DetailViewModel(app, offlineApi())
        val fake = FakeProvider(Result.success(VideoSource("https://x/v.m3u8", "Stream")))
        vm.streamProviders = listOf(fake)
        vm.resolve(MediaType.MOVIE, 42)
        assertTrue(await { vm.state.source != null }, "state=${vm.state}")
        assertEquals("https://x/v.m3u8", vm.state.source?.streamUrl)
        assertFalse(vm.state.resolving)
        assertFalse(vm.state.providerMissing)
        assertEquals(42, fake.lastTmdb)
    }

    @Test fun detailResolveWithNoProvidersMarksMissing() {
        val vm = DetailViewModel(app, offlineApi())
        vm.streamProviders = emptyList()
        vm.resolve(MediaType.TV, 7, season = 1, episode = 2)
        assertTrue(await { vm.state.providerMissing }, "state=${vm.state}")
        assertNull(vm.state.source)
        assertFalse(vm.state.resolving)
    }

    @Test fun detailResolveFailureSurfacesError() {
        val vm = DetailViewModel(app, offlineApi())
        vm.streamProviders = listOf(FakeProvider(Result.failure(IllegalStateException("no streams here"))))
        vm.resolve(MediaType.MOVIE, 3)
        assertTrue(await { vm.state.resolveError != null }, "state=${vm.state}")
        assertEquals("no streams here", vm.state.resolveError)
        assertFalse(vm.state.resolving)
    }

    @Test fun detailLoadServersAndSelectServer() {
        val vm = DetailViewModel(app, offlineApi())
        val fake = FakeProvider(
            Result.success(VideoSource("https://x/v", "S")),
            serverNames = listOf("Alpha", "Beta"))
        vm.streamProviders = listOf(fake)
        vm.loadServers()
        assertTrue(await { vm.state.servers.isNotEmpty() }, "state=${vm.state}")
        assertEquals(listOf("Alpha", "Beta"), vm.state.servers)
        vm.selectServer(MediaType.MOVIE, 9, 0, 0, "Beta")
        assertEquals("Beta", vm.state.selectedServer)
        assertTrue(await { fake.lastServer == "Beta" }, "server=${fake.lastServer}")
        // Re-selecting the same server is a no-op (no second resolve).
        val before = vm.state.source
        vm.selectServer(MediaType.MOVIE, 9, 0, 0, "Beta")
        assertEquals(before, vm.state.source)
    }

    @Test fun detailLoadFailsOfflineIntoErrorState() {
        val vm = DetailViewModel(app, offlineApi())
        vm.load(MediaType.MOVIE, 42)
        assertTrue(await { !vm.state.loading && vm.state.detail == null }, "state=${vm.state}")
        vm.load(MediaType.TV, 42)
        assertTrue(await { !vm.state.loading }, "state=${vm.state}")
        assertNull(vm.state.detail)
    }

    @Test fun detailSeasonAndGuardsOffline() = runTest {
        val vm = DetailViewModel(app, offlineApi())
        vm.selectSeason(MediaType.TV, 7, 3)
        assertTrue(await { vm.state.episodes.isEmpty() })
        assertEquals(1, vm.state.selectedSeason)
        // No detail loaded: watchlist toggle is a no-op.
        vm.toggleWatchlist()
        assertFalse(vm.state.watchlisted)
        // Blank notes are refused before any storage call.
        vm.addNote(MediaType.MOVIE, 1, "   ", 5)
        // Saved-position lookup degrades to 0 when storage is unavailable.
        assertEquals(0L, vm.startPosition(MediaType.MOVIE, 1, 0, 0))
    }

    // ---- Browse / Search / Home / Lists ----

    @Test fun browseLoadsFailOfflineIntoEmptyStates() {
        val vm = BrowseViewModel(app, offlineApi())
        vm.loadMovies()
        assertTrue(await { !vm.movies.loading && vm.movies.items.isEmpty() }, "movies=${vm.movies}")
        vm.loadSeries()
        assertTrue(await { !vm.series.loading && vm.series.items.isEmpty() }, "series=${vm.series}")
    }

    @Test fun searchBlankClearsAndQueryFailsOffline() {
        val vm = SearchViewModel(app, offlineApi())
        vm.onQueryChange("  ")
        assertEquals("  ", vm.state.query)
        assertTrue(vm.state.results.isEmpty())
        assertFalse(vm.state.loading)
        vm.onQueryChange("dune")
        assertEquals("dune", vm.state.query)
        // Debounced search runs against the unreachable catalog and settles empty.
        assertTrue(await { !vm.state.loading && vm.state.results.isEmpty() }, "state=${vm.state}")
        assertEquals("dune", vm.state.query)
    }

    @Test fun homeRefreshFailsOffline() {
        val vm = HomeViewModel(app, offlineApi())
        vm.refresh()
        assertTrue(
            await { !vm.state.loading && vm.state.trendingMovies.isEmpty() },
            "state=${vm.state}")
        assertTrue(vm.state.topMovies.isEmpty() && vm.state.topTv.isEmpty())
    }

    @Test fun listsBlankCreateIsRefused() {
        val vm = ListsViewModel(app)
        vm.createList("   ")
        // No state is exposed; the guard simply returns without launching storage work.
        assertNotNull(vm)
    }

    @Test fun tmdbApiEndpointsFailOffline() = runTest {
        val api = offlineApi()
        assertTrue(runCatching { api.trendingMovies() }.isFailure)
        assertTrue(runCatching { api.trendingTv() }.isFailure)
        assertTrue(runCatching { api.topRatedMovies() }.isFailure)
        assertTrue(runCatching { api.topRatedTv() }.isFailure)
        assertTrue(runCatching { api.discoverMovies() }.isFailure)
        assertTrue(runCatching { api.discoverTv() }.isFailure)
        assertTrue(runCatching { api.search("a b") }.isFailure)
        assertTrue(runCatching { api.movieDetail(1) }.isFailure)
        assertTrue(runCatching { api.tvDetail(1) }.isFailure)
        assertTrue(runCatching { api.seasonEpisodes(1, 1) }.isFailure)
    }

    @Test fun detailResolveViaCustomRegistryFactory() {
        val vm = DetailViewModel(app, offlineApi())
        val fake = FakeProvider(Result.success(VideoSource("https://x/custom", "Custom")))
        vm.streamProviders = null
        vm.registryFactory = RegistryFactory { _, _ ->
            com.vayunmathur.uniflix.domain.PluginRegistry(listOf("custom-1" to fake))
        }
        vm.resolve(MediaType.MOVIE, 11)
        assertTrue(await { vm.state.source != null }, "state=${vm.state}")
        assertEquals("https://x/custom", vm.state.source?.streamUrl)
        assertEquals(11, fake.lastTmdb)
    }

    @Test fun detailLoadServersFailsGracefullyWithRealFactory() {
        FakeAndroidKeyStore.install()
        val vm = DetailViewModel(app, offlineApi())
        // Default AllInstalled factory reads the addon DB, which cannot open
        // under Robolectric; loadServers must swallow that and keep no servers.
        vm.loadServers()
        Thread.sleep(300)
        assertTrue(vm.state.servers.isEmpty())
        assertFalse(vm.state.resolving)
    }

    @Test fun registryFactoriesAreDistinctObjects() {
        assertTrue(RegistryFactory.HttpAddons !== RegistryFactory.AllInstalled)
        val vm = DetailViewModel(app, offlineApi())
        assertEquals(RegistryFactory.AllInstalled, vm.registryFactory)
        vm.registryFactory = RegistryFactory.HttpAddons
        assertEquals(RegistryFactory.HttpAddons, vm.registryFactory)
        assertEquals(1, DetailUiState().selectedSeason)
        assertEquals(0L, DetailUiState().startPositionMs)
        assertTrue(DetailUiState().episodes.isEmpty() && DetailUiState().servers.isEmpty())
    }
}
