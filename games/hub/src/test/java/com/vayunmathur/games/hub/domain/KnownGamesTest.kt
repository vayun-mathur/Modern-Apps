package com.vayunmathur.games.hub.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnownGamesTest {

    @Test
    fun registeredNameWinsOverCatalog() {
        assertEquals("Chess Pro", KnownGames.displayName("chess", "Chess Pro"))
    }

    @Test
    fun catalogNameUsedWhenNotRegisteredOrBlank() {
        assertEquals("Unblock Jam", KnownGames.displayName("unblockjam"))
        assertEquals("Logic Gates", KnownGames.displayName("logicgate", ""))
        assertEquals("Logic Gates", KnownGames.displayName("logicgate", "  "))
    }

    @Test
    fun unknownIdIsMadeReadableNeverRaw() {
        assertEquals("Space race", KnownGames.displayName("space_race"))
        assertEquals("Tic tac", KnownGames.displayName("tic-tac"))
        assertEquals("X", KnownGames.displayName("x"))
    }

    @Test
    fun catalogCoversTheTwelveQueriedGamesWithMatchingPackages() {
        assertEquals(12, KnownGames.all.size)
        assertEquals(KnownGames.all.size, KnownGames.all.map { it.gameId }.toSet().size)
        KnownGames.all.forEach { assertEquals("com.vayunmathur.games.${it.gameId}", it.packageName) }
        assertEquals("com.vayunmathur.games.kart", KnownGames.find("kart")?.packageName)
        assertNull(KnownGames.find("nope"))
    }
}
