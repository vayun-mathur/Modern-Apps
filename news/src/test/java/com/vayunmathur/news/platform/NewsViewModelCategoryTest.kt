package com.vayunmathur.news.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.news.data.NewsPrefs
import com.vayunmathur.news.data.NewsRepository
import com.vayunmathur.news.domain.NewsCategory
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.seedNewsDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Category filter at the ViewModel seam: the feed narrows by the
 * source->category catalog mapping, "All" (null) passes the whole
 * enabled corpus through, and search runs within the selected category
 * (changing category re-filters active search results).
 */
@RunWith(RobolectricTestRunner::class)
class NewsViewModelCategoryTest {

    private val serverJson = """
        {"generated_at":"2026-11-23T12:00:00Z","articles":[
          {"title":"Zyxvolcano world","url":"https://srv.example/zyx-world","source":"bbc",
           "published_at":"2026-11-23T10:00:00Z"},
          {"title":"Zyxvolcano tech","url":"https://srv.example/zyx-tech","source":"techcrunch",
           "published_at":"2026-11-23T09:00:00Z"},
          {"title":"Zyxvolcano local","url":"https://srv.example/zyx-local","source":"gothamist",
           "published_at":"2026-11-23T08:00:00Z"}
        ]}
    """.trimIndent()

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    /** Poll (with main-looper pumping) until [predicate] holds or ~5s elapse. */
    private fun await(description: String, predicate: () -> Boolean) {
        repeat(100) {
            idle()
            if (predicate()) return
            Thread.sleep(50)
        }
        assertTrue(predicate(), description)
    }

    @Test fun categoryFilterAllPassthroughAndSearchWithinCategory() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        seedNewsDb(context)
        val repository = NewsRepository(context, fetch = { url ->
            if (url.contains("news.json")) serverJson else throw IOException("offline")
        })
        // Pre-land the server feed in Room so the ViewModel flows have data
        // regardless of its init-refresh timing.
        runBlocking { repository.refresh(SourceCatalog.sources) }
        val viewModel = NewsViewModel(context, repository, NewsPrefs(context))
        // stateIn(WhileSubscribed) flows only compute while collected.
        val collectors = CoroutineScope(Dispatchers.Default)
        collectors.launch { viewModel.articles.collect {} }
        collectors.launch { viewModel.searchResults.collect {} }
        try {
            val ours = listOf(
                "https://srv.example/zyx-world",
                "https://srv.example/zyx-tech",
                "https://srv.example/zyx-local",
            )
            await("refresh did not land") {
                viewModel.articles.value.map { it.url }.containsAll(ours)
            }

            // Category filter: only the TechCrunch (Technology) article of ours stays.
            viewModel.setCategoryFilter(NewsCategory.TECHNOLOGY)
            await("technology filter did not apply") {
                viewModel.articles.value.any { it.url == "https://srv.example/zyx-tech" } &&
                    viewModel.articles.value.none { it.url in ours - "https://srv.example/zyx-tech" }
            }
            assertTrue(
                viewModel.articles.value.all {
                    SourceCatalog.matchesCategory(it.sourceId, NewsCategory.TECHNOLOGY)
                },
            )

            // "All" passthrough: our whole corpus is back.
            viewModel.setCategoryFilter(null)
            await("all passthrough did not restore") {
                viewModel.articles.value.map { it.url }.containsAll(ours)
            }

            // Search within category: with Technology selected, the shared
            // title term finds only the tech hit.
            viewModel.setCategoryFilter(NewsCategory.TECHNOLOGY)
            viewModel.setSearchQuery("zyxvolcano")
            ShadowSystemClock.advanceBy(Duration.ofMillis(300)); idle()
            await("search did not return the tech hit") {
                viewModel.searchResults.value.any { it.url == "https://srv.example/zyx-tech" }
            }
            assertEquals(
                listOf("https://srv.example/zyx-tech"),
                viewModel.searchResults.value.map { it.url }.filter { it in ours },
            )

            // Changing category re-filters the active search without re-typing.
            viewModel.setCategoryFilter(NewsCategory.WORLD)
            await("category change did not re-filter search") {
                viewModel.searchResults.value.map { it.url }.filter { it in ours } ==
                    listOf("https://srv.example/zyx-world")
            }
            viewModel.setCategoryFilter(null)
            await("all did not restore search hits") {
                viewModel.searchResults.value.map { it.url }.containsAll(ours)
            }
        } finally {
            collectors.cancel()
        }
    }
}
