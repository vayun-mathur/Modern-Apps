package com.vayunmathur.news.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.news.domain.SourceCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repository over Room (in the test DB) with a fake fetch seam: no network.
 * The fake serves a sitemap index, its child urlset, and one article page.
 */
@RunWith(RobolectricTestRunner::class)
class NewsRepositoryTest {
    @org.junit.Before fun setUp() { com.vayunmathur.news.seedNewsDb(ApplicationProvider.getApplicationContext()) }

    private val sitemapIndex = """
        <sitemapindex><sitemap><loc>https://fake.example/child.xml</loc></sitemap></sitemapindex>
    """.trimIndent()
    private val childUrlset = """
        <urlset xmlns:news="http://www.google.com/schemas/sitemap-news/0.9">
          <url><loc>https://fake.example/story</loc>
            <news:news><news:publication_date>2026-11-23T10:00:00+00:00</news:publication_date>
            <news:title>Fake Story</news:title></news:news></url>
        </urlset>
    """.trimIndent()
    private val articleHtml = """
        <html><head><meta property="og:title" content="Fake Story"/></head><body><article>
        <p>This is the body of the fake story, long enough to count as real article prose here.</p>
        <p>And a second paragraph so the extracted body has more than one block to return.</p>
        </article></body></html>
    """.trimIndent()

    private fun fakeFetch(calls: MutableList<String>): suspend (String) -> String = { url ->
        calls += url
        when {
            url.endsWith("child.xml") -> childUrlset
            url.endsWith("/story") -> articleHtml
            else -> sitemapIndex
        }
    }

    @Test
    fun refreshFollowsIndexAndCachesArticles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = mutableListOf<String>()
        val repo = NewsRepository(context, fakeFetch(calls), serverFeedFetch = { throw java.io.IOException("offline") })
        val source = SourceCatalog.byId("bbc")!!.copy(feedUrl = "https://fake.example/index.xml")
        val failed = repo.refresh(listOf(source))
        assertTrue(failed.isEmpty())
        assertTrue(calls.any { it.endsWith("child.xml") })
        val cached = kotlinx.coroutines.withTimeout(10_000) { repo.articles.first { it.isNotEmpty() } }
        // The test DB is shared across this class, so assert presence, not exclusivity.
        assertTrue(cached.any { it.title == "Fake Story" })
    }

    @Test
    fun articleBodyExtractsAndCaches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = mutableListOf<String>()
        val repo = NewsRepository(context, fakeFetch(calls), serverFeedFetch = { throw java.io.IOException("offline") })
        val body = repo.articleBody("https://fake.example/story")
        assertEquals("Fake Story", body.title)
        assertEquals(2, body.blocks.size)
        val callsAfterFirst = calls.size
        repo.articleBody("https://fake.example/story")
        assertEquals(callsAfterFirst, calls.size, "second read must come from the Room cache")
    }

    @Test
    fun fastSourcePublishesWhileHangingSourceTimesOut() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fastUrlset = """
            <urlset><url><loc>https://fake.example/fast-story</loc>
            <news:title>Fast Story</news:title></url></urlset>
        """.trimIndent()
        val repo = NewsRepository(
            context,
            fetch = { url ->
                if (url.contains("hang")) kotlinx.coroutines.delay(Long.MAX_VALUE)
                fastUrlset
            },
            sourceTimeoutMs = 500,
            serverFeedFetch = { throw java.io.IOException("offline") },
        )
        val fast = SourceCatalog.byId("bbc")!!.copy(name = "Fast", feedUrl = "https://fake.example/fast.xml")
        val hanging = SourceCatalog.byId("guardian")!!.copy(name = "Hang", feedUrl = "https://fake.example/hang.xml")
        val refreshJob = async { repo.refresh(listOf(hanging, fast)) }
        // Cache-first / incremental: the fast source's articles are visible
        // from the Room flow before the refresh as a whole completes.
        val cached = kotlinx.coroutines.withTimeout(10_000) {
            repo.articles.first { articles -> articles.any { it.title == "Fast Story" } }
        }
        assertTrue(cached.any { it.title == "Fast Story" })
        assertTrue(refreshJob.isActive, "fast articles must publish while the hanging source is still in flight")
        val failed = kotlinx.coroutines.withTimeout(10_000) { refreshJob.await() }
        assertEquals(listOf("Hang"), failed)
    }

    @Test
    fun cachedArticlesEmitWithoutRefresh() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = mutableListOf<String>()
        val repo = NewsRepository(context, fakeFetch(calls), serverFeedFetch = { throw java.io.IOException("offline") })
        val source = SourceCatalog.byId("bbc")!!.copy(feedUrl = "https://fake.example/index.xml")
        repo.refresh(listOf(source))
        // No refresh in flight: the Room cache alone must serve the feed.
        val cached = repo.articles.first { it.isNotEmpty() }
        assertTrue(cached.any { it.title == "Fake Story" })
    }

    @Test
    fun feedFetchRunsOffMainThread() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mainThreadName = Thread.currentThread().name
        val seenThreads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val repo = NewsRepository(context, fetch = { _ ->
            seenThreads += Thread.currentThread().name
            childUrlset
        }, serverFeedFetch = { throw java.io.IOException("offline") })
        val source = SourceCatalog.byId("bbc")!!.copy(feedUrl = "https://fake.example/direct.xml")
        repo.refresh(listOf(source))
        assertTrue(seenThreads.isNotEmpty())
        assertTrue(
            seenThreads.none { it == mainThreadName },
            "feed fetch/parse must not run on the main thread, saw $seenThreads",
        )
    }

    @Test
    fun failingSourceIsReportedNotThrown() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NewsRepository(context, fetch = { throw java.io.IOException("offline") }, serverFeedFetch = { throw java.io.IOException("offline") })
        val failed = repo.refresh(listOf(SourceCatalog.sources.first()))
        assertEquals(1, failed.size)
    }
}
