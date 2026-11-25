package com.vayunmathur.news.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.network.Networking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Server-primary refresh, fallback decision, all-sources inclusion, and on-device search. */
@RunWith(RobolectricTestRunner::class)
class NewsServerFeedTest {
    @org.junit.Before fun setUp() { com.vayunmathur.news.seedNewsDb(ApplicationProvider.getApplicationContext()) }

    private val serverJson = """
        {"generated_at":"2026-11-23T12:00:00Z","articles":[
          {"title":"Server Alpha Volcano","url":"https://srv.example/alpha","source":"bbc",
           "published_at":"2026-11-23T10:00:00Z"},
          {"title":"Server Beta","url":"https://srv.example/beta","source":"guardian",
           "published_at":"2026-11-23T09:00:00Z"}
        ]}
    """.trimIndent()

    private val fallbackUrlset = """
        <urlset><url><loc>https://srv.example/fallback-story</loc>
        <news:title>Fallback Story</news:title></url></urlset>
    """.trimIndent()

    private val articleHtml = """
        <html><head><meta property="og:title" content="Server Beta"/></head><body><article>
        <p>Deep inside this downloaded body there is a quixotic paragraph about tides and harbours.</p>
        <p>Second paragraph keeps the extraction comfortably above the minimum block count here.</p>
        </article></body></html>
    """.trimIndent()

    @Test fun refreshPrefersServerFeedAndDoesNotCrawl() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = mutableListOf<String>()
        var serverCalls = 0
        val repo = NewsRepository(
            context,
            fetch = { url -> calls += url; serverJson },
            serverFeedFetch = { serverCalls++; serverJson },
        )
        val failed = repo.refresh(SourceCatalog.sources)
        assertTrue(failed.isEmpty())
        assertEquals(1, serverCalls, "primary path must be the single server-feed fetch")
        assertTrue(calls.isEmpty(), "server feed success must not crawl sources, saw $calls")
        val cached = withTimeout(10_000) { repo.articles.first { a -> a.any { it.url == "https://srv.example/alpha" } } }
        assertTrue(cached.any { it.title == "Server Alpha Volcano" })
    }

    @Test fun staticFeedUrlIsTheR2CopyWithOriginFallback() {
        // The contract the server publishes to (location_share_server
        // NEWS_R2_KEY) and the app's fallback order.
        assertEquals("https://static.maproj.org/news/v1/news.json", Networking.STATIC_FEED_URL)
        assertEquals("https://api.maproj.org/news.json", Networking.SERVER_FEED_URL)
        assertTrue(Networking.STATIC_MAX_AGE_MS > 0)
    }

    @Test fun refreshIncludesServerArticlesFromEverySource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val filterJson = """
            {"generated_at":"2026-11-23T12:00:00Z","articles":[
              {"title":"Filter Kept","url":"https://srv.example/filter-kept","source":"bbc",
               "published_at":"2026-11-23T10:00:00Z"},
              {"title":"Filter Also Kept","url":"https://srv.example/filter-dropped","source":"guardian",
               "published_at":"2026-11-23T09:00:00Z"}
            ]}
        """.trimIndent()
        val repo = NewsRepository(context, fetch = { filterJson }, serverFeedFetch = { filterJson })
        // No enabled-source filter exists: even though only BBC drives
        // the (unused) fallback list, the Guardian server article lands.
        val failed = repo.refresh(listOf(SourceCatalog.byId("bbc")!!))
        assertTrue(failed.isEmpty())
        val cached = withTimeout(10_000) { repo.articles.first { a -> a.any { it.url == "https://srv.example/filter-kept" } } }
        assertTrue(cached.any { it.url == "https://srv.example/filter-dropped" })
    }

    @Test fun serverFailureFallsBackToSourceCrawl() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = mutableListOf<String>()
        val repo = NewsRepository(
            context,
            fetch = { url -> calls += url; fallbackUrlset },
            serverFeedFetch = { calls += Networking.SERVER_FEED_URL; throw IOException("server unreachable") },
        )
        val bbc = SourceCatalog.byId("bbc")!!.copy(feedUrl = "https://srv.example/fallback.xml")
        val failed = repo.refresh(listOf(bbc))
        assertTrue(failed.isEmpty(), "fallback crawl success must not report failure")
        assertTrue(calls.first() == Networking.SERVER_FEED_URL, "server must be tried first")
        assertTrue(calls.any { it.endsWith("fallback.xml") }, "fallback must crawl the given source")
        val cached = withTimeout(10_000) { repo.articles.first { a -> a.any { it.title == "Fallback Story" } } }
        assertTrue(cached.any { it.title == "Fallback Story" })
    }

    @Test fun searchRanksTitleHitBeforeDownloadedBodyHit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NewsRepository(
            context,
            fetch = { url -> if (url.endsWith("/beta")) articleHtml else serverJson },
            serverFeedFetch = { serverJson },
        )
        repo.refresh(SourceCatalog.sources)
        withTimeout(10_000) { repo.articles.first { a -> a.any { it.url == "https://srv.example/beta" } } }
        // Title hit: "volcano" is only in Alpha's title.
        val titleResults = repo.search("volcano")
        assertEquals("https://srv.example/alpha", titleResults.firstOrNull()?.url)
        // Body hit: download Beta's body, then a body-only term must find it,
        // ranked after a title hit for a shared term is covered in NewsSearchTest.
        repo.articleBody("https://srv.example/beta")
        val bodyResults = repo.search("quixotic")
        assertEquals(listOf("https://srv.example/beta"), bodyResults.map { it.url }.filter { it.endsWith("/beta") })
        // Source-name search finds that source's stubs.
        val sourceResults = repo.search("guardian")
        assertTrue(sourceResults.any { it.url == "https://srv.example/beta" })
    }
}
