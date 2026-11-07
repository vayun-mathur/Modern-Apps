package com.vayunmathur.games.chess.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pass 3: generated data-class surface (copy/equals/hash) of the Lichess DTOs. */
class LichessDtoPass3Test {

    @Test fun gameFullDataClassSurface() {
        val full = GameFull(
            id = "g1", rated = true,
            variant = LichessVariant("standard"),
            clock = LichessClock(300000, 3000),
            white = LichessPlayer("a", "Alice"),
            black = LichessPlayer("b", "Bob"),
            initialFen = "startpos",
            state = GameState(moves = "e2e4", wtime = 290000, status = "started"),
        )
        assertEquals(full, full.copy())
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertNotEquals(full, full.copy(id = "g2"))
        assertEquals("g2", full.copy(id = "g2").id)
        val (id, rated) = full
        assertEquals("g1", id)
        assertTrue(rated)
        assertTrue(full.toString().contains("g1"))
        assertEquals("standard", full.variant.key)
        assertEquals(3000, full.clock!!.increment)
        assertEquals(GameFull(), GameFull(id = "", rated = false))
    }

    @Test fun envelopeAndPlayerDataClassSurface() {
        val game = LichessEventGame("g1", "white", LichessOpponent("opp"), isMyTurn = true)
        assertEquals(game, game.copy())
        assertEquals("opp", game.opponent!!.username)
        assertNotEquals(game, game.copy(color = "black"))
        assertEquals(LichessEventGame(), LichessEventGame("", "", null, false))

        val challenge = LichessChallenge("c1", LichessOpponent("carol"), LichessOpponent("dave"))
        assertEquals(challenge, challenge.copy())
        assertEquals("dave", challenge.destUser!!.username)
        assertEquals(LichessOpponent(), LichessOpponent(""))

        val envelope = LichessEventEnvelope("gameStart", game, challenge)
        assertEquals(envelope, envelope.copy())
        assertEquals("gameStart", envelope.type)
        assertEquals(LichessEventEnvelope(), LichessEventEnvelope("", null, null))

        val player = LichessPlayer("id1", "Name")
        assertEquals(player, player.copy())
        assertEquals("id1", player.component1())
        assertEquals(LichessProfile("x", "y"), LichessProfile("x", "y").copy())
    }

    @Test fun gameStateDataClassSurface() {
        val state = GameState(
            type = "gameState", moves = "e2e4 e7e5", wtime = 1000, btime = 2000,
            winc = 0, binc = 0, status = "mate", winner = "white",
        )
        assertEquals(state, state.copy())
        assertEquals(state.hashCode(), state.copy().hashCode())
        assertNotEquals(state, state.copy(winner = "black"))
        assertEquals("black", state.copy(winner = "black").winner)
        assertEquals(GameState(), GameState("", "", 0, 0, 0, 0, "started", null))
        val clock = LichessClock(1000, 50)
        assertEquals(clock, clock.copy())
        assertEquals(50, clock.component2())
    }
}
