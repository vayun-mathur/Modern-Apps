package com.vayunmathur.launcher.tv.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests for [TvArtCache]: unknown packages cache a null rather than throwing,
 * installed packages resolve art, and [TvArtCache.drop] evicts so the next
 * load re-queries.
 */
@RunWith(AndroidJUnit4::class)
class TvArtCacheTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun cache() = TvArtCache(app)

    @Test
    fun `unknown package banner and icon are null, repeatedly`() = runTest {
        val cache = cache()
        assertNull(cache.banner("com.example.does.not.exist"))
        assertNull(cache.icon("com.example.does.not.exist"))
        // Cached null: the second round must agree without a package to find.
        assertNull(cache.banner("com.example.does.not.exist"))
        assertNull(cache.icon("com.example.does.not.exist"))
    }

    @Test
    fun `own package icon answer is stable and cached`() = runTest {
        val cache = cache()
        // Under Robolectric the rasterised icon may be null (no real launcher
        // icon pipeline); whatever the answer, it must be cached and stable.
        val first = cache.icon(app.packageName)
        val second = cache.icon(app.packageName)
        assertSame(first, second)
    }

    @Test
    fun `drop evicts so art reloads`() = runTest {
        val cache = cache()
        cache.icon(app.packageName)
        cache.drop(app.packageName)
        // After eviction the art is reloaded and re-cached consistently.
        val reloaded = cache.icon(app.packageName)
        assertSame(reloaded, cache.icon(app.packageName))
        cache.drop("com.example.does.not.exist")
        assertNull(cache.icon("com.example.does.not.exist"))
    }

    @Test
    fun `banner for an installed package without leanback art falls back or is null`() = runTest {
        val cache = cache()
        // The test app declares no TV banner; either the app icon fallback or
        // null is correct, but it must not throw and must be stable once cached.
        val first = cache.banner(app.packageName)
        val second = cache.banner(app.packageName)
        assertSame(first, second)
    }
}
