package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Small model rules: Play URL building, TV-incompatible learning disabled on
 * phones is covered by Robolectric tests; here the pure pieces.
 */
class PlayStoreLinksAndModelsTest {

    @Test
    fun `play url carries the package id`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.app",
            PlayStoreLinks.playStoreUrl("com.example.app"),
        )
    }

    @Test
    fun `installed info defaults to enabled with no provenance`() {
        val info = InstalledInfo(
            packageName = "com.example.app",
            name = "Example",
            versionName = "1.0",
            versionCode = 1L,
        )

        assertTrue(info.enabled)
        assertNull(info.installerPackage)
        assertNull(info.updateOwnerPackage)
        assertEquals(0L, info.lastUpdateTime)
    }

    @Test
    fun `partitioned updates concatenation is sorted by name`() {
        val partitioned = PartitionedUpdates(
            eligible = listOf(
                UnifiedApp("com.example.z", AppSource.FDROID, "Zulu", versionCode = 2L),
            ),
            foreign = listOf(
                UnifiedApp("com.example.a", AppSource.PLAYSTORE, "Alpha", versionCode = 2L),
            ),
        )

        assertEquals(
            listOf("Alpha", "Zulu"),
            partitioned.actionable.map { it.name },
        )
    }

    @Test
    fun `empty partition has no actionable rows`() {
        assertTrue(PartitionedUpdates().actionable.isEmpty())
        assertTrue(PartitionedUpdates().eligible.isEmpty())
        assertTrue(PartitionedUpdates().foreign.isEmpty())
    }

    @Test
    fun `reproducible badge set answers membership`() {
        val verified = ReproducibleBuilds.Verified(setOf("com.example.app:42"))

        assertTrue(verified.contains("com.example.app", 42))
        assertFalse(verified.contains("com.example.app", 43))
        assertFalse(verified.contains("com.example.other", 42))
        assertEquals(1, verified.size)
    }

    @Test
    fun `split file defaults carry no hash or size`() {
        val split = SplitFile(fileName = "base.apk", url = "https://x.org/base.apk")

        assertNull(split.sha256)
        assertEquals(0L, split.size)
    }
}
