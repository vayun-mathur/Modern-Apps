package com.vayunmathur.news.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Prefs: Local location persistence and retired-state cleanup. */
@RunWith(RobolectricTestRunner::class)
class NewsPrefsSortTest {

    @Test fun legacyCleanupDropsRetiredStateWithoutCrashing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = NewsPrefs(context)
        // Retired per-source / feed-sort state is dropped without crashing.
        prefs.clearLegacySourcePrefs()
        prefs.setLocalLocation("Chicago, Illinois, United States")
        assertEquals("Chicago, Illinois, United States", prefs.localLocation.first())
    }

    @Test fun localLocationDefaultsUnsetAndPersistsAndClears() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = NewsPrefs(context)
        prefs.setLocalLocation(null)
        assertNull(prefs.localLocation.first())
        prefs.setLocalLocation("  Chicago, Illinois, United States  ")
        assertEquals("Chicago, Illinois, United States", prefs.localLocation.first())
        // Free-typed place names persist verbatim (trimmed).
        prefs.setLocalLocation("Manchester")
        assertEquals("Manchester", prefs.localLocation.first())
        prefs.setLocalLocation("   ")
        assertNull(prefs.localLocation.first())
        prefs.setLocalLocation("California, United States")
        assertEquals("California, United States", prefs.localLocation.first())
        prefs.setLocalLocation(null)
        assertNull(prefs.localLocation.first())
    }
}
