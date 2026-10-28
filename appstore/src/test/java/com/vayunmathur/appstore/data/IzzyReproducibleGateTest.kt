package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IzzyOnDroid lists only versions an independent verification builder reproduced
 * bit-for-bit (issue #492). The verdicts come from rbtlog's per-builder `index.json`
 * files — opaque content-hash keys mapping to build records — so the unit under test
 * is the admission rule, not the network fetch.
 */
class IzzyReproducibleGateTest {

    private fun verified(vararg keys: String) =
        IzzyReproducibleBuilds.Verified(keys.toSet())

    @Test
    fun `admits a version a builder reproduced`() {
        val feed = verified("com.example.app:42")

        assertTrue(IzzyReproducibleBuilds.admits(feed, "com.example.app", 42))
    }

    @Test
    fun `rejects a version no builder reproduced`() {
        val feed = verified("com.example.app:42")

        assertFalse(IzzyReproducibleBuilds.admits(feed, "com.example.app", 43))
        assertFalse(IzzyReproducibleBuilds.admits(feed, "com.example.other", 42))
    }

    @Test
    fun `rejects everything when the feed failed to load`() {
        // Fail-closed: a missing feed imports nothing, never everything unfiltered.
        assertFalse(IzzyReproducibleBuilds.admits(null, "com.example.app", 42))
        assertEquals(0, IzzyReproducibleBuilds.Verified(emptySet()).size)
    }

    @Test
    fun `a version code match is exact, not a prefix`() {
        val feed = verified("com.example.app:4")

        assertFalse(IzzyReproducibleBuilds.admits(feed, "com.example.app", 42))
    }

    @Test
    fun `verdicts from either builder admit`() {
        // Izzy's own builder and the independent one merge into one set.
        val feed = verified("com.example.a:1", "com.example.b:2")

        assertTrue(IzzyReproducibleBuilds.admits(feed, "com.example.a", 1))
        assertTrue(IzzyReproducibleBuilds.admits(feed, "com.example.b", 2))
    }
}
