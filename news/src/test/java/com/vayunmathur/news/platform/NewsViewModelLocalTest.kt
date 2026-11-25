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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local ordering at the ViewModel seam: with a location set, the Local
 * feed puts that location's sources first (non-matching locals follow);
 * unset, the Local order is the plain ranked order.
 */
@RunWith(RobolectricTestRunner::class)
class NewsViewModelLocalTest {

    // Newest first: NY, then Chicago, then LA — ranked order without a location.
    private val serverJson = """
        {"generated_at":"2026-11-23T12:00:00Z","articles":[
          {"title":"Local NY story","url":"https://srv.example/local-ny","source":"gothamist",
           "published_at":"2026-11-23T11:00:00Z"},
          {"title":"Local Chicago story","url":"https://srv.example/local-chi","source":"block-club",
           "published_at":"2026-11-23T10:00:00Z"},
          {"title":"Local LA story","url":"https://srv.example/local-la","source":"laist",
           "published_at":"2026-11-23T09:00:00Z"}
        ]}
    """.trimIndent()

    private val ours = listOf(
        "https://srv.example/local-ny",
        "https://srv.example/local-chi",
        "https://srv.example/local-la",
    )

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(description: String, predicate: () -> Boolean) {
        repeat(100) {
            idle()
            if (predicate()) return
            Thread.sleep(50)
        }
        assertTrue(predicate(), description)
    }

    @Test fun localLocationBoostsMatchingSourcesFirstAndUnsetRestores() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        seedNewsDb(context)
        val repository = NewsRepository(
            context,
            fetch = { throw IOException("offline") },
            serverFeedFetch = { serverJson },
        )
        runBlocking { repository.refresh(SourceCatalog.sources) }
        val prefs = NewsPrefs(context)
        val viewModel = NewsViewModel(context, repository, prefs)
        val collectors = CoroutineScope(Dispatchers.Default)
        collectors.launch { viewModel.articles.collect {} }
        collectors.launch { viewModel.localLocation.collect {} }
        try {
            // Clear via the ViewModel (its scope is pumped by await's looper
            // idling; a runBlocking DataStore edit on the Robolectric main
            // thread deadlocks against the queued ViewModel coroutines).
            viewModel.setLocalLocation(null)
            await("local location did not clear") { viewModel.localLocation.value == null }
            viewModel.setCategoryFilter(NewsCategory.LOCAL)
            await("local feed did not land") {
                viewModel.articles.value.map { it.url }.containsAll(ours)
            }
            // Unset: plain ranked (newest-first) order among our articles.
            await("unset order was not ranked order") {
                viewModel.articles.value.map { it.url }.filter { it in ours } == ours
            }
            // Set Chicago: its source jumps first, others keep ranked order.
            viewModel.setLocalLocation("Chicago, Illinois, United States")
            await("chicago boost did not apply") {
                viewModel.articles.value.map { it.url }.filter { it in ours } ==
                    listOf(ours[1], ours[0], ours[2])
            }
            assertEquals("Chicago, Illinois, United States", viewModel.localLocation.value)
            // Clearing restores the unset order.
            viewModel.setLocalLocation(null)
            await("clearing did not restore ranked order") {
                viewModel.articles.value.map { it.url }.filter { it in ours } == ours
            }
        } finally {
            collectors.cancel()
        }
    }
}
