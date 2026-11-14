package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.uniflix.data.TmdbApi
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.StreamProvider
import com.vayunmathur.uniflix.domain.VideoSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** TMDB + detail success paths through the fake transport (no real network). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9TmdbSuccessTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        val routes: List<Pair<String, String>> = listOf(
            "/trending/movie" to """{"results":[{"id":1,"title":"Trending Film","release_date":"2025-03-04","vote_average":8.1}]}""",
            "/trending/tv" to """{"results":[{"id":2,"name":"Trending Show","first_air_date":"2024-01-02","media_type":"tv"}]}""",
            "/top/movie" to """{"results":[{"id":3,"title":"Top Film"}]}""",
            "/top/tv" to """{"results":[]}""",
            "/discover/movie" to """{"results":[{"id":4,"title":"Discovered"}]}""",
            "/discover/tv" to """{"results":[]}""",
            "/search?" to """{"results":[{"id":5,"title":"Search Hit"}]}""",
            "/movie/10/recommendations" to """{"results":[{"id":11,"title":"Rec Film"}]}""",
            "/movie/10" to """{"id":10,"title":"Detail Film","release_date":"2023-05-06","overview":"A film.","runtime":121,"vote_average":7.5}""",
            "/season/" to """{"episodes":[{"episode_number":1,"name":"Pilot","overview":"First."},{"episode_number":2,"name":"Second"}]}""",
            "/tv/20" to """{"id":20,"name":"Detail Show","first_air_date":"2022-02-02","seasons":[{"season_number":0,"name":"Specials","episode_count":1},{"season_number":1,"name":"Season 1","episode_count":2}]}""",
            "/stream/" to """{"streams":[{"url":"https://cdn.example.invalid/v.m3u8","name":"HD"}]}""",
            "/manifest.json" to """{"id":"demo","name":"Demo Addon","version":"1.0.0","resources":["stream"],"types":["movie","series"]}""",
        )
        uniflixTransport = { url ->
            val body = routes.firstOrNull { url.contains(it.first) }?.second
            if (body != null) fakeHttpResponse(200, body) else fakeHttpResponse(404, "{}")
        }
    }

    @AfterTest fun tearDown() {
        uniflixTransport = null
    }

    @Test fun tmdb_listsAndSearch() = runBlocking {
        val api = TmdbApi()
        assertEquals("Trending Film", api.trendingMovies().single().title)
        assertEquals(MediaType.TV, api.trendingTv().single().mediaType)
        assertEquals("Top Film", api.topRatedMovies().single().title)
        assertTrue(api.topRatedTv().isEmpty())
        assertEquals("Discovered", api.discoverMovies().single().title)
        assertEquals("Search Hit", api.search("hit").single().title)
    }

    @Test fun tmdb_detailsAndEpisodes() = runBlocking {
        val api = TmdbApi()
        val movie = api.movieDetail(10)
        assertEquals("Detail Film", movie.title)
        assertEquals(2023, movie.year)
        assertEquals(121, movie.runtimeMinutes)
        assertEquals("Rec Film", movie.recommendations.single().title)
        val show = api.tvDetail(20)
        assertEquals("Detail Show", show.title)
        assertEquals(2, show.seasons.size)
        val episodes = api.seasonEpisodes(20, 1)
        assertEquals(listOf("Pilot", "Second"), episodes.map { it.name })
    }

    @Test fun tmdb_errorStatus_throws() {
        runBlocking {
            val api = TmdbApi()
            assertFailsWith<IllegalStateException> { api.movieDetail(999) }
        }
    }

    private fun await(cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test fun detailViewModel_loadWatchlistResolve() {
        val vm = DetailViewModel(app)
        vm.load(MediaType.MOVIE, 10)
        assertTrue(await { vm.state.detail?.title == "Detail Film" }, "state=${vm.state}")
        assertFalse(vm.state.loading)
        vm.toggleWatchlist()
        assertTrue(await { vm.state.watchlisted }, "watchlist did not toggle on")
        vm.toggleWatchlist()
        assertTrue(await { !vm.state.watchlisted }, "watchlist did not toggle off")

        vm.streamProviders = listOf(object : StreamProvider() {
            override val name = "FakeServer"
            override val supports4k = true
            override suspend fun resolve(tmdbId: Int, mediaType: MediaType, season: Int, episode: Int, server: String?): VideoSource =
                VideoSource(streamUrl = "https://cdn.example.invalid/v.m3u8", title = "HD Stream")
            override suspend fun servers(): List<String> = listOf("FakeServer")
        })
        vm.loadServers()
        assertTrue(await { vm.state.servers == listOf("FakeServer") }, "servers=${vm.state.servers}")
        vm.resolve(MediaType.MOVIE, 10)
        assertTrue(await { vm.state.source?.title == "HD Stream" }, "source=${vm.state.source}")
        assertNotNull(vm.state.source)
    }

    @Test fun detailViewModel_tvLoadAndSeason() {
        val vm = DetailViewModel(app)
        vm.load(MediaType.TV, 20)
        assertTrue(await { vm.state.detail?.title == "Detail Show" }, "state=${vm.state}")
        assertEquals(2, vm.state.episodes.size)
        vm.selectSeason(20, 1)
        assertTrue(await { vm.state.selectedSeason == 1 && vm.state.episodes.size == 2 })
    }

    @Test fun detailViewModel_notesRoundTrip() {
        val vm = DetailViewModel(app)
        vm.load(MediaType.MOVIE, 10)
        assertTrue(await { vm.state.detail?.title == "Detail Film" }, "state=${vm.state}")
        vm.addNote(MediaType.MOVIE, 10, "Great pacing", 5)
        assertTrue(await {
            kotlinx.coroutines.runBlocking {
                vm.notesFor(10, MediaType.MOVIE).first().any { it.body == "Great pacing" }
            }
        }, "note not stored")
        val note = kotlinx.coroutines.runBlocking {
            vm.notesFor(10, MediaType.MOVIE).first().single()
        }
        assertEquals(5, note.score)
        vm.deleteNote(note)
        assertTrue(await {
            kotlinx.coroutines.runBlocking {
                vm.notesFor(10, MediaType.MOVIE).first().isEmpty()
            }
        }, "note not deleted")
        assertEquals(0L, kotlinx.coroutines.runBlocking { vm.startPosition(MediaType.MOVIE, 10, 0, 0) })
        vm.savePosition(MediaType.MOVIE, 10, "Detail Film", null, 0, 0, 42_000L, 120_000L)
        assertTrue(await {
            kotlinx.coroutines.runBlocking { vm.startPosition(MediaType.MOVIE, 10, 0, 0) } == 42_000L
        }, "position not saved")
        vm.addNote(MediaType.MOVIE, 10, "   ", 1)
        assertTrue(await {
            kotlinx.coroutines.runBlocking {
                vm.notesFor(10, MediaType.MOVIE).first().isEmpty()
            }
        }, "blank note must be ignored")
    }

    @Test fun detailViewModel_noProviders_missing() {
        val vm = DetailViewModel(app)
        vm.streamProviders = emptyList()
        vm.resolve(MediaType.MOVIE, 10)
        assertTrue(await { vm.state.providerMissing || vm.state.resolveError != null }, "state=${vm.state}")
    }
}
