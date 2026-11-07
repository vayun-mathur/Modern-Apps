package com.vayunmathur.games.chess.network

import com.vayunmathur.games.chess.Route
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pass 3: Lichess DTO decoding, UCI helpers, terminal statuses, Route keys. */
class LichessModelsPass3Test {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun uciParsingAndFormatting() {
        assertEquals(UciMove(Position(6, 4), Position(4, 4)), parseUci("e2e4"))
        assertEquals(UciMove(Position(1, 4), Position(0, 4), PieceType.QUEEN), parseUci("e7e8q"))
        assertEquals("e7e8q", parseUci("e7e8q").toString())
        assertEquals("e1g1", UciMove(Position(7, 4), Position(7, 6)).toString())
        assertEquals(UciMove(Position(0, 0), Position(7, 7), PieceType.KNIGHT), parseUci("a8h1n"))
        assertFailsWith<IllegalArgumentException> { parseUci("e2e") }
        assertFailsWith<IllegalArgumentException> { parseUci("e2e4e5") }
        assertFailsWith<IllegalArgumentException> { parseUci("e7e8x") }
        assertEquals(Position(0, 0), Position(7, 0).let { squareFromUci("a8") })
        assertEquals("a8", Position(0, 0).toUciSquare())
        assertEquals("h1", Position(7, 7).toUciSquare())

        assertEquals(emptyList(), splitUciMoves(""))
        assertEquals(emptyList(), splitUciMoves("   "))
        assertEquals(listOf("e2e4", "e7e5"), splitUciMoves("e2e4 e7e5"))
    }

    @Test fun colorAndPromotionMappings() {
        assertEquals(PieceColor.WHITE, colorFromLichess("white"))
        assertEquals(PieceColor.BLACK, colorFromLichess("black"))
        assertFailsWith<IllegalArgumentException> { colorFromLichess("green") }
        assertEquals("white", PieceColor.WHITE.toLichessColor())
        assertEquals("black", PieceColor.BLACK.toLichessColor())
        assertEquals('q', PieceType.QUEEN.toUciPromotion())
        assertEquals('r', PieceType.ROOK.toUciPromotion())
        assertEquals('b', PieceType.BISHOP.toUciPromotion())
        assertEquals('n', PieceType.KNIGHT.toUciPromotion())
        assertEquals('q', PieceType.KING.toUciPromotion())
        assertEquals('q', PieceType.PAWN.toUciPromotion())
    }

    @Test fun envelopeDecodingVariants() {
        fun env(text: String) = json.decodeFromString(LichessEventEnvelope.serializer(), text).toEvent()
        assertEquals(
            LichessEvent.GameStart("g1", PieceColor.BLACK, "opp"),
            env("""{"type":"gameStart","game":{"gameId":"g1","color":"black","opponent":{"username":"opp"}}}"""),
        )
        assertEquals(LichessEvent.GameFinish("g2"), env("""{"type":"gameFinish","game":{"gameId":"g2"}}"""))
        assertEquals(
            LichessEvent.Challenge("c1", "carol"),
            env("""{"type":"challenge","challenge":{"id":"c1","challenger":{"username":"carol"}}}"""),
        )
        assertEquals(
            LichessEvent.ChallengeDeclined("c2"),
            env("""{"type":"challengeDeclined","challenge":{"id":"c2"}}"""),
        )
        assertEquals(LichessEvent.Ignored, env("""{"type":"friendOnline"}"""))
        assertEquals(LichessEvent.Ignored, env("""{}"""))
        // Missing nested payloads degrade to empty defaults, never crash.
        assertEquals(LichessEvent.GameStart("", PieceColor.WHITE, ""), env("""{"type":"gameStart"}"""))
    }

    @Test fun gameLineDecodingAndTerminalStatuses() {
        fun line(text: String) = decodeGameLine(json.parseToJsonElement(text), json)
        val full = line(
            """{"type":"gameFull","id":"g9","rated":true,"white":{"id":"a","name":"A"},"black":{"id":"b","name":"B"},"state":{"moves":"e2e4","status":"started","wtime":1000}}"""
        ) as LichessGameUpdate.Full
        assertEquals("g9", full.game.id)
        assertTrue(full.game.rated)
        assertEquals(PieceColor.WHITE, full.game.myColor("a"))
        assertEquals(PieceColor.BLACK, full.game.myColor("b"))
        assertEquals(PieceColor.WHITE, full.game.myColor("someone-else"))
        val state = line("""{"type":"gameState","moves":"","status":"mate","winner":"black"}""") as LichessGameUpdate.State
        assertTrue(state.state.isTerminal)
        assertEquals(LichessGameUpdate.Ignored, line("""{"type":"chatLine"}"""))
        assertEquals(LichessGameUpdate.Ignored, line("""[]"""))
        assertEquals(LichessGameUpdate.Ignored, line("""{"other":1}"""))

        for (status in listOf("aborted", "mate", "resign", "stalemate", "timeout", "draw", "outoftime", "cheat", "noStart", "variantEnd")) {
            assertTrue(GameState(status = status).isTerminal, status)
        }
        assertFalse(GameState(status = "started").isTerminal)
        assertFalse(GameState(status = "created").isTerminal)
        val clock = LichessClock(60000, 2000)
        assertEquals(60000, clock.initial)
        assertEquals(LichessVariant().key, "standard")
    }

    @Test fun timeControlsAndProfileDefaults() {
        assertEquals("1+0", LichessTimeControl.BULLET.label)
        assertEquals("3+2", LichessTimeControl.BLITZ_3_2.label)
        assertEquals("10+0", LichessTimeControl.RAPID_10_0.label)
        assertEquals(
            listOf(
                LichessTimeControl.BULLET, LichessTimeControl.BLITZ_3_2,
                LichessTimeControl.RAPID_5_3, LichessTimeControl.RAPID_10_0,
            ),
            LichessTimeControl.PRESETS,
        )
        assertEquals(LichessProfile(), LichessProfile("", ""))
        assertEquals(LichessPlayer().name, "")
    }

    @Test fun routesSerializeAndCompare() {
        assertEquals(Route.Game, Route.Game)
        assertEquals(Route.Online, Route.Online)
        assertEquals(Route.Puzzles, Route.Puzzles)
        assertEquals(Route.Learn, Route.Learn)
        assertEquals(Route.GameCenter, Route.GameCenter)
        val stage = Route.LearnStage("chessPieces", "rook")
        assertEquals("chessPieces", stage.categoryKey)
        assertEquals("rook", stage.stageKey)
        assertEquals(stage, Route.LearnStage("chessPieces", "rook"))
        assertTrue(Route.Game != Route.Online)

        val encoded = kotlinx.serialization.json.Json.encodeToString(Route.serializer(), stage)
        assertEquals(stage, kotlinx.serialization.json.Json.decodeFromString(Route.serializer(), encoded))
        assertEquals(
            Route.Game,
            kotlinx.serialization.json.Json.decodeFromString(
                Route.serializer(),
                kotlinx.serialization.json.Json.encodeToString(Route.serializer(), Route.Game),
            ),
        )
    }
}
