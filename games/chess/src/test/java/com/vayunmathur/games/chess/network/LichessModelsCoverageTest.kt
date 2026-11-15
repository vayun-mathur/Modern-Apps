package com.vayunmathur.games.chess.network

import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Envelope defaults, colour/promotion mappings and game-line decoding edges. */
class LichessModelsCoverageTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun `event envelopes tolerate missing payloads and map every kind`() {
        fun event(payload: String) = json.decodeFromString(LichessEventEnvelope.serializer(), payload).toEvent()
        val declined = event("""{"type":"challengeDeclined","challenge":{"id":"c9"}}""")
        assertIs<LichessEvent.ChallengeDeclined>(declined)
        assertEquals("c9", declined.id)
        val challenge = event("""{"type":"challenge","challenge":{"id":"c1","challenger":{"username":"eve"},"destUser":{"username":"ada"}}}""")
        assertIs<LichessEvent.Challenge>(challenge)
        assertEquals("eve", challenge.challenger)
        val bareStart = event("""{"type":"gameStart"}""")
        assertIs<LichessEvent.GameStart>(bareStart)
        assertEquals("", bareStart.gameId)
        assertEquals(PieceColor.WHITE, bareStart.color, "a missing colour defaults to white")
        val blackStart = event("""{"type":"gameStart","game":{"gameId":"g","color":"black","opponent":{"username":"zed"}}}""")
        assertIs<LichessEvent.GameStart>(blackStart)
        assertEquals(PieceColor.BLACK, blackStart.color)
        assertIs<LichessEvent.Ignored>(event("""{"type":"challengeCancelled","challenge":{"id":"x"}}"""))
    }

    @Test fun `uci helpers cover defensive branches`() {
        assertEquals(PieceType.KNIGHT, parseUci("b7b8k").promotion, "the never-sent k maps to knight")
        assertEquals('q', PieceType.KING.toUciPromotion())
        assertEquals('q', PieceType.PAWN.toUciPromotion())
        assertEquals('r', PieceType.ROOK.toUciPromotion())
        assertEquals("white", PieceColor.WHITE.toLichessColor())
        assertEquals("black", PieceColor.BLACK.toLichessColor())
        assertFailsWith<IllegalArgumentException> { promotionFromUci('x') }
        assertEquals("a1", Position(7, 0).toUciSquare())
        assertEquals("h8", Position(0, 7).toUciSquare())
        assertEquals(UciMove(Position(6, 4), Position(4, 4)), parseUci("e2e4"))
        assertNull(parseUci("e2e4").promotion)
        assertEquals(listOf("e2e4"), splitUciMoves("  e2e4  "))
    }

    @Test fun `game full colour resolution and profile defaults`() {
        val both = GameFull(white = LichessPlayer(id = "me"), black = LichessPlayer(id = "me"))
        assertEquals(PieceColor.WHITE, both.myColor("me"), "an id on both sides resolves to white")
        val neither = GameFull(white = LichessPlayer(id = "a"), black = LichessPlayer(id = "b"))
        assertEquals(PieceColor.WHITE, neither.myColor("stranger"))
        val profile = json.decodeFromString(LichessProfile.serializer(), """{}""")
        assertEquals("", profile.id)
        assertEquals("", profile.username)
        assertFalse(GameState(status = "created").isTerminal)
        assertTrue(GameState(status = "noStart").isTerminal)
        assertTrue(GameState(status = "variantEnd").isTerminal)
        assertEquals("5+3", LichessTimeControl.RAPID_5_3.label)
        assertEquals(10, LichessTimeControl.RAPID_10_0.minutes)
    }

    @Test fun `decodeGameLine survives non-object and typeless payloads`() {
        assertIs<LichessGameUpdate.Ignored>(decodeGameLine(json.parseToJsonElement("""[]"""), json))
        assertIs<LichessGameUpdate.Ignored>(decodeGameLine(json.parseToJsonElement("""{"moves":"e2e4"}"""), json))
        assertIs<LichessGameUpdate.Ignored>(decodeGameLine(json.parseToJsonElement("""{"type":42}"""), json))
        val state = decodeGameLine(
            json.parseToJsonElement("""{"type":"gameState","moves":"e2e4","status":"mate","winner":"black"}"""), json)
        assertIs<LichessGameUpdate.State>(state)
        assertEquals("black", state.state.winner)
        assertTrue(state.state.isTerminal)
    }
}
