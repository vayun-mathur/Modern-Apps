package com.vayunmathur.auto.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto module: [PinnedAppsPrefs] — the car dock's pinned order. Empty until
 * the user pins; writes truncate to [PinnedAppsPrefs.MAX_PINNED]; stale ids
 * never appear by construction (the UI intersects on load).
 */
@RunWith(RobolectricTestRunner::class)
class PinnedAppsPrefsTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `empty until the user pins something`() = runTest {
        PinnedAppsPrefs.setPinned(context, emptyList())
        assertEquals(emptyList(), PinnedAppsPrefs.pinnedNow(context))
        assertEquals(emptyList(), PinnedAppsPrefs.pinnedFlow(context).first())
    }

    @Test
    fun `pin order round-trips through the flow`() = runTest {
        val ids = listOf("com.a/.Car", "com.b/.Car")
        PinnedAppsPrefs.setPinned(context, ids)
        assertEquals(ids, PinnedAppsPrefs.pinnedNow(context))
        assertEquals(ids, PinnedAppsPrefs.pinnedFlow(context).first())
        PinnedAppsPrefs.setPinned(context, emptyList())
    }

    @Test
    fun `writes truncate to the max dock size`() = runTest {
        val ids = (1..8).map { "com.app$it/.Car" }
        PinnedAppsPrefs.setPinned(context, ids)
        assertEquals(ids.take(PinnedAppsPrefs.MAX_PINNED), PinnedAppsPrefs.pinnedNow(context))
        PinnedAppsPrefs.setPinned(context, emptyList())
    }

    @Test
    fun `pin appends and rejects when full`() = runTest {
        PinnedAppsPrefs.setPinned(context, emptyList())
        assertTrue(PinnedAppsPrefs.pin(context, "com.a/.Car"))
        // Re-pinning is a no-op success.
        assertTrue(PinnedAppsPrefs.pin(context, "com.a/.Car"))
        assertEquals(listOf("com.a/.Car"), PinnedAppsPrefs.pinnedNow(context))
        PinnedAppsPrefs.setPinned(context, (1..PinnedAppsPrefs.MAX_PINNED).map { "com.app$it/.Car" })
        assertFalse(PinnedAppsPrefs.pin(context, "com.full/.Car"))
        assertEquals(PinnedAppsPrefs.MAX_PINNED, PinnedAppsPrefs.pinnedNow(context).size)
        PinnedAppsPrefs.setPinned(context, emptyList())
    }

    @Test
    fun `unpin and move reorder the dock`() = runTest {
        PinnedAppsPrefs.setPinned(context, listOf("a", "b", "c"))
        PinnedAppsPrefs.unpin(context, "b")
        assertEquals(listOf("a", "c"), PinnedAppsPrefs.pinnedNow(context))
        // Unpinning an absent id is a no-op.
        PinnedAppsPrefs.unpin(context, "zzz")
        assertEquals(listOf("a", "c"), PinnedAppsPrefs.pinnedNow(context))
        PinnedAppsPrefs.move(context, "c", 0)
        assertEquals(listOf("c", "a"), PinnedAppsPrefs.pinnedNow(context))
        // Moving an absent id is a no-op; out-of-range indices clamp.
        PinnedAppsPrefs.move(context, "zzz", 0)
        PinnedAppsPrefs.move(context, "a", 99)
        assertEquals(listOf("c", "a"), PinnedAppsPrefs.pinnedNow(context))
        PinnedAppsPrefs.setPinned(context, emptyList())
    }
}
