package com.vayunmathur.launcher.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearLauncherProtocolTest {

    @Test
    fun goldenSnapshotJson() {
        val snapshot = WearFavoritesSnapshot(
            generatedAtMillis = 1_000L,
            favorites = listOf(
                WearFavorite("com.example.calendar", "com.example.calendar.Main", "Calendar", 0),
            ),
            truncated = false,
        )
        assertEquals(
            """{"generatedAtMillis":1000,"favorites":[{"packageName":"com.example.calendar",""" +
                """"className":"com.example.calendar.Main","label":"Calendar"}]}""",
            WearLauncherProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = WearFavoritesSnapshot(
            generatedAtMillis = 9L,
            favorites = listOf(WearFavorite("com.example.notes", "", "Notes", 1)),
            truncated = true,
        )
        val decoded = WearLauncherProtocol.json.decodeFromString<WearFavoritesSnapshot>(
            WearLauncherProtocol.json.encodeToString(snapshot),
        )
        assertEquals(snapshot, decoded)
    }

    @Test
    fun buildSortsByRankDedupesAndReRanks() {
        val snapshot = buildWearFavorites(
            candidates = listOf(
                WearFavorite("com.example.b", "", "B", 2),
                WearFavorite("com.example.a", "", "A", 1),
                WearFavorite("com.example.a", "", "A duplicate", 0),
                WearFavorite("", "", "blank", 0),
            ),
            generatedAtMillis = 5L,
        )
        // Duplicate keeps lowest rank (0), then re-ranked in rank order.
        assertEquals(listOf("com.example.a", "com.example.b"), snapshot.favorites.map { it.packageName })
        assertEquals(listOf(0, 1), snapshot.favorites.map { it.rank })
        assertEquals("A duplicate", snapshot.favorites[0].label)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun buildCapsCountAndMarksTruncated() {
        val candidates = (0 until WearLauncherProtocol.MAX_FAVORITES + 3).map {
            WearFavorite("com.example.$it", "", "App $it", it)
        }
        val snapshot = buildWearFavorites(candidates, generatedAtMillis = 1L)
        assertEquals(WearLauncherProtocol.MAX_FAVORITES, snapshot.favorites.size)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun companionPresenceIsCountBased() {
        assertFalse(wearLauncherCompanionPresent(0))
        assertTrue(wearLauncherCompanionPresent(1))
    }
}
