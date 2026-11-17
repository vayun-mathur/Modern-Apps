package com.vayunmathur.appstore.domain
import com.vayunmathur.appstore.platform.rank

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #595's two reported queries, held as the acceptance test.
 *
 * The candidate lists are what the reporter said the store showed them: "Google Maps"
 * returned Facebook, GMaps WV, Messenger, Netflix and Snapchat, and "chase mobile"
 * returned nothing at all.
 */
class SearchRankingTest {

    private fun app(
        name: String,
        packageName: String,
        author: String = "",
        summary: String = "",
        popularity: Long = 0L,
    ) = SearchRanking.Candidate(name, packageName, author, summary, popularity)

    private fun names(results: List<SearchRanking.Candidate>) = results.map { it.name }

    private fun rank(items: List<SearchRanking.Candidate>, query: String) =
        SearchRanking.rank(items, query) { it }

    private val playResultsForGoogleMaps = listOf(
        app("Facebook", "com.facebook.katana", "Meta Platforms", popularity = 5_000_000_000),
        app("GMaps WV", "com.gmapswv", "Divested Computing", "Google Maps in a WebView"),
        app("Messenger", "com.facebook.orca", "Meta Platforms", popularity = 4_000_000_000),
        app("Netflix", "com.netflix.mediaclient", "Netflix, Inc.", popularity = 1_000_000_000),
        app("Snapchat", "com.snapchat.android", "Snap Inc.", popularity = 1_000_000_000),
        app("Google Maps", "com.google.android.apps.maps", "Google LLC", popularity = 10_000_000_000),
    )

    @Test
    fun `Google Maps is the first result and the unrelated apps are dropped`() {
        val ranked = rank(playResultsForGoogleMaps, "Google Maps")

        assertEquals("Google Maps", names(ranked).first())
        assertTrue("Facebook" !in names(ranked))
        assertTrue("Netflix" !in names(ranked))
        assertTrue("Snapchat" !in names(ranked))
    }

    @Test
    fun `a listing named Maps by Google still answers Google Maps`() {
        val ranked = rank(
            playResultsForGoogleMaps + app("Maps", "com.google.android.apps.maps2", "Google LLC"),
            "Google Maps",
        )

        assertTrue("Maps" in names(ranked))
    }

    @Test
    fun `chase mobile finds the app whose name it is`() {
        val results = listOf(
            app("Chase Mobile", "com.chase.sig.android", "JPMorgan Chase & Co.", popularity = 50_000_000),
            app("Chase Bank Locations", "com.locations.chase"),
            app("T-Mobile", "com.tmobile.pr.mytmobile", popularity = 100_000_000),
        )

        assertEquals("Chase Mobile", names(rank(results, "chase mobile")).first())
    }

    @Test
    fun `chase mobile falls back to partial matches rather than an empty screen`() {
        val results = listOf(
            app("Chase Bank", "com.chase.sig.android", "JPMorgan Chase & Co."),
            app("Netflix", "com.netflix.mediaclient"),
        )

        assertEquals(listOf("Chase Bank"), names(rank(results, "chase mobile")))
    }

    @Test
    fun `an exact name beats a more popular partial match`() {
        val results = listOf(
            app("Signal Booster", "com.example.booster", popularity = 9_000_000),
            app("Signal", "org.thoughtcrime.securesms", popularity = 100_000_000),
        )

        assertEquals("Signal", names(rank(results, "signal")).first())
    }

    @Test
    fun `a package name typed in full wins`() {
        val results = listOf(
            app("Maps Deluxe", "com.example.maps", popularity = 9_000_000),
            app("Organic Maps", "app.organicmaps"),
        )

        assertEquals("Organic Maps", names(rank(results, "app.organicmaps")).first())
    }

    @Test
    fun `a blank query is left alone`() {
        assertEquals(playResultsForGoogleMaps, rank(playResultsForGoogleMaps, "   "))
    }
}
