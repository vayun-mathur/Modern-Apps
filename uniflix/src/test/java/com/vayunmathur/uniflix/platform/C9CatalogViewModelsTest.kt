package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.domain.MediaType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Catalog ViewModels (home/browse/search) through the fake TMDB transport. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9CatalogViewModelsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        uniflixTransport = { url ->
            when {
                url.contains("/trending/movie") -> fakeHttpResponse(200, """{"results":[{"id":1,"title":"Trending Film"}]}""")
                url.contains("/trending/tv") -> fakeHttpResponse(200, """{"results":[{"id":2,"name":"Trending Show","media_type":"tv"}]}""")
                url.contains("/top/movie") -> fakeHttpResponse(200, """{"results":[{"id":3,"title":"Top Film"}]}""")
                url.contains("/top/tv") -> fakeHttpResponse(200, """{"results":[{"id":4,"name":"Top Show","media_type":"tv"}]}""")
                url.contains("/discover/movie") -> fakeHttpResponse(200, """{"results":[{"id":5,"title":"Browse Film"}]}""")
                url.contains("/discover/tv") -> fakeHttpResponse(200, """{"results":[{"id":6,"name":"Browse Show","media_type":"tv"}]}""")
                url.contains("/search?") -> fakeHttpResponse(200, """{"results":[{"id":7,"title":"Search Hit"}]}""")
                else -> fakeHttpResponse(404, "{}")
            }
        }
    }

    @AfterTest fun tearDown() {
        uniflixTransport = null
    }

    private fun await(cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            scheduler.advanceUntilIdle()
            ShadowLooper.idleMainLooper()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test fun home_loadsRows() {
        val vm = HomeViewModel(app)
        assertTrue(await { vm.state.trendingMovies.isNotEmpty() }, "state=${vm.state}")
        assertEquals("Trending Film", vm.state.trendingMovies.single().title)
        assertEquals("Top Show", vm.state.topTv.single().title)
        vm.refresh()
        assertTrue(await { !vm.state.loading })
    }

    @Test fun browse_loadsMoviesAndSeries() {
        val vm = BrowseViewModel(app)
        vm.loadMovies()
        assertTrue(await { vm.movies.items.isNotEmpty() }, "movies=${vm.movies}")
        assertEquals(MediaType.MOVIE, vm.movies.items.first().mediaType)
        vm.loadSeries()
        assertTrue(await { vm.series.items.firstOrNull()?.title == "Browse Show" }, "series=${vm.series}")
    }

    @Test fun search_debouncedResultsAndBlankClears() {
        val vm = SearchViewModel(app)
        vm.onQueryChange("hit")
        assertTrue(await { vm.state.results.isNotEmpty() }, "state=${vm.state}")
        assertEquals("Search Hit", vm.state.results.single().title)
        vm.onQueryChange("")
        assertEquals("", vm.state.query)
        assertTrue(vm.state.results.isEmpty())
    }
}
