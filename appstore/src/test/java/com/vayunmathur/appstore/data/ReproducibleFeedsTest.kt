package com.vayunmathur.appstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reproducibility verdict feeds: Izzy's is an admission gate
 * (no feed, or no verdict for the version, admits nothing), F-Droid's
 * is a badge lookup. Offline, Izzy's fetch degrades to an empty
 * verdict set while F-Droid's propagates the download failure —
 * matching how each caller treats them.
 */
@RunWith(RobolectricTestRunner::class)
class ReproducibleFeedsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `izzy admits only versions with an explicit verdict`() {
        val verified = IzzyReproducibleBuilds.Verified(setOf("com.example.a:7", "com.example.b:1"))
        assertTrue(IzzyReproducibleBuilds.admits(verified, "com.example.a", 7L))
        assertFalse(IzzyReproducibleBuilds.admits(verified, "com.example.a", 8L))
        assertFalse(IzzyReproducibleBuilds.admits(verified, "com.example.z", 7L))
        assertFalse(IzzyReproducibleBuilds.admits(null, "com.example.a", 7L))
        assertFalse(IzzyReproducibleBuilds.admits(IzzyReproducibleBuilds.Verified(emptySet()), "com.example.a", 7L))
        assertEquals(2, verified.size)
        assertTrue(verified.contains("com.example.b", 1L))
    }

    @Test
    fun `fdroid verified set answers by package and version`() {
        val verified = ReproducibleBuilds.Verified(setOf("com.example.a:7"))
        assertTrue(verified.contains("com.example.a", 7L))
        assertFalse(verified.contains("com.example.a", 6L))
        assertFalse(verified.contains("com.example.b", 7L))
        assertEquals(1, verified.size)
    }

    @Test
    fun `izzy fetch offline degrades to no verdicts`() = runBlocking {
        val verified = IzzyReproducibleBuilds.fetch(context)
        assertEquals(0, verified.size)
        assertFalse(IzzyReproducibleBuilds.admits(verified, "com.example.a", 1L))
    }

    @Test
    fun `fdroid fetch offline propagates the download failure`() = runBlocking {
        assertFailsWith<Exception> { ReproducibleBuilds.fetch(context) }
        Unit
    }
}
