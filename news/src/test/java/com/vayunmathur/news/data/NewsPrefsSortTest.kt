package com.vayunmathur.news.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.news.domain.FeedSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feed-sort persistence: defaults to Top stories and round-trips. */
@RunWith(RobolectricTestRunner::class)
class NewsPrefsSortTest {

    @Test fun feedSortDefaultsToTopAndPersists() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = NewsPrefs(context)
        prefs.setFeedSort(FeedSort.LATEST)
        assertEquals(FeedSort.LATEST, prefs.feedSort.first())
        prefs.setFeedSort(FeedSort.TOP)
        assertEquals(FeedSort.TOP, prefs.feedSort.first())
        // Retired per-source toggle state is dropped without crashing.
        prefs.clearLegacySourcePrefs()
        assertEquals(FeedSort.TOP, prefs.feedSort.first())
    }
}
