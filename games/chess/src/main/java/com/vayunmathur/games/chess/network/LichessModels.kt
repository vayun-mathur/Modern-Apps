package com.vayunmathur.games.chess.network

import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Lichess Board API DTOs (`https://lichess.org/api#tag/Board`) plus UCI helpers.
 *
 * UCI moves are coordinate strings: `e2e4`, promotion `e7e8q`, castling `e1g1`. The server sends
 * `gameState.moves` as a space-separated suffix of every move so far; the ViewModel diff-applies
 * only the unseen tail onto [com.vayunmathur.games.chess.data.Board].
 *
 * Event envelopes are polymorphic (`type`: `gameStart`/`gameFinish`/`challenge`/…), so the
 * client decodes NDJSON lines as [LichessEventEnvelope] and switches on [type]; game payloads
 * arrive as [GameFull] (first line) then [GameState] deltas. Unknown `type` values decode via
 * [IgnoredEvent] rather than failing — Lichess adds event kinds without versioning.
 */

private const val UCI_LENGTH = 4
private const val UCI_PROMOTION_LENGTH = 5
private const val RANK_COUNT = 8
private const val SQUARE_END = 2
private const val MOVE_END = 4
private const val PROMOTION_INDEX = 4

/** `e2` → [Position]: row 0 is rank 8 (matches [com.vayunmathur.games.chess.data.Board]). */
internal fun squareFromUci(square: String): Position {
    // Server-supplied squares outside a1..h8 are a protocol violation,
    // rejected here so parseUci's callers can skip the token instead of
    // indexing the board out of bounds.
    require(square.length == 2 && square[0] in 'a'..'h' && square[1] in '1'..'8') {
        "Bad UCI square: $square"
    }
    return Position(RANK_COUNT - (square[1] - '0'), square[0] - 'a')
}

/** [Position] → `e2`. */
internal fun Position.toUciSquare(): String =
    "${('a'.code + col).toChar()}${RANK_COUNT - row}"

/** `q`/`r`/`b`/`n` → [PieceType]; anything else is a protocol violation. */
internal fun promotionFromUci(letter: Char): PieceType = when (letter) {
    'q' -> PieceType.QUEEN
    'r' -> PieceType.ROOK
    'b' -> PieceType.BISHOP
    'n', 'k' -> PieceType.KNIGHT // 'k' never occurs; mapped defensively, never emitted
    else -> throw IllegalArgumentException("Bad promotion letter: $letter")
}

/** [PieceType] → `q`/`r`/`b`/`n` (king/pawn never promote; map to queen defensively). */
internal fun PieceType.toUciPromotion(): Char = when (this) {
    PieceType.QUEEN -> 'q'
    PieceType.ROOK -> 'r'
    PieceType.BISHOP -> 'b'
    PieceType.KNIGHT -> 'n'
    PieceType.KING, PieceType.PAWN -> 'q'
}

/** A parsed UCI move: from/to squares plus the optional promotion choice. */
data class UciMove(val from: Position, val to: Position, val promotion: PieceType? = null) {
    override fun toString(): String =
        from.toUciSquare() + to.toUciSquare() + (promotion?.toUciPromotion()?.toString() ?: "")
}

/** Parses `e2e4` / `e7e8q` / `e1g1`; throws [IllegalArgumentException] on malformed input. */
fun parseUci(uci: String): UciMove {
    require(uci.length == UCI_LENGTH || uci.length == UCI_PROMOTION_LENGTH) { "Bad UCI: $uci" }
    return UciMove(
        from = squareFromUci(uci.substring(0, SQUARE_END)),
        to = squareFromUci(uci.substring(SQUARE_END, MOVE_END)),
        promotion = uci.getOrNull(PROMOTION_INDEX)?.let(::promotionFromUci),
    )
}

/** Splits a `gameState.moves` payload into UCI tokens (empty string → no moves). */
fun splitUciMoves(moves: String): List<String> =
    if (moves.isBlank()) emptyList() else moves.trim().split(' ')

/** `white`/`black` → [PieceColor]; anything else is a protocol violation. */
fun colorFromLichess(color: String): PieceColor = when (color) {
    "white" -> PieceColor.WHITE
    "black" -> PieceColor.BLACK
    else -> throw IllegalArgumentException("Bad color: $color")
}

/** [PieceColor] → `white`/`black` for seek/challenge request bodies. */
fun PieceColor.toLichessColor(): String = if (this == PieceColor.WHITE) "white" else "black"

/** Immutable value describing lichess profile. */
@Serializable
data class LichessProfile(
    val id: String = "",
    val username: String = "",
)

/** Polymorphic event-stream envelope; [payload] is decoded per [type]. */
@Serializable
data class LichessEventEnvelope(
    val type: String = "",
    @SerialName("game") val game: LichessEventGame? = null,
    @SerialName("challenge") val challenge: LichessChallenge? = null,
)

/** Immutable value describing lichess event game. */
@Serializable
data class LichessEventGame(
    @SerialName("gameId") val gameId: String = "",
    val color: String = "",
    val opponent: LichessOpponent? = null,
    @SerialName("isMyTurn") val isMyTurn: Boolean = false,
)

/** Immutable value describing lichess opponent. */
@Serializable
data class LichessOpponent(
    val username: String = "",
)

/** Immutable value describing lichess challenge. */
@Serializable
data class LichessChallenge(
    val id: String = "",
    val challenger: LichessOpponent? = null,
    val destUser: LichessOpponent? = null,
)

/** Decoded event — unknown `type` values become [Ignored] so new server kinds don't crash us. */
sealed interface LichessEvent {
    /** Immutable value describing game start; carries gameId, color, opponent. */
    data class GameStart(val gameId: String, val color: PieceColor, val opponent: String) : LichessEvent
    /** Immutable value describing game finish; carries gameId. */
    data class GameFinish(val gameId: String) : LichessEvent
    /** Immutable value describing challenge; carries id, challenger. */
    data class Challenge(val id: String, val challenger: String) : LichessEvent
    /** Immutable value describing challenge declined; carries id. */
    data class ChallengeDeclined(val id: String) : LichessEvent
    /** Singleton holding ignored state and operations. */
    data object Ignored : LichessEvent
}

fun LichessEventEnvelope.toEvent(): LichessEvent = when (type) {
    "gameStart" -> LichessEvent.GameStart(
        gameId = game?.gameId ?: "",
        color = game?.color?.let(::colorFromLichess) ?: PieceColor.WHITE,
        opponent = game?.opponent?.username ?: "",
    )
    "gameFinish" -> LichessEvent.GameFinish(gameId = game?.gameId ?: "")
    "challenge" -> LichessEvent.Challenge(
        id = challenge?.id ?: "",
        challenger = challenge?.challenger?.username ?: "",
    )
    "challengeDeclined" -> LichessEvent.ChallengeDeclined(id = challenge?.id ?: "")
    else -> LichessEvent.Ignored
}

/** First line of the game stream: full position plus clock and side to move. */
@Serializable
data class GameFull(
    val id: String = "",
    val rated: Boolean = false,
    val variant: LichessVariant = LichessVariant(),
    val clock: LichessClock? = null,
    val white: LichessPlayer = LichessPlayer(),
    val black: LichessPlayer = LichessPlayer(),
    @SerialName("initialFen") val initialFen: String = "startpos",
    val state: GameState = GameState(),
) {
    /** My color, resolved from the account id Lichess echoes on both player objects. */
    fun myColor(myUserId: String): PieceColor =
        if (black.id == myUserId && white.id != myUserId) PieceColor.BLACK else PieceColor.WHITE
}

/** Immutable value describing lichess variant. */
@Serializable
data class LichessVariant(
    val key: String = "standard",
)

/** Immutable value describing lichess clock. */
@Serializable
data class LichessClock(
    val initial: Long = 0L,
    val increment: Long = 0L,
)

/** Immutable value describing lichess player. */
@Serializable
data class LichessPlayer(
    val id: String = "",
    val name: String = "",
)

/** `gameFull.state` and every `gameState` line: authoritative move list, clocks, status. */
@Serializable
data class GameState(
    val type: String = "",
    val moves: String = "",
    val wtime: Long = 0L,
    val btime: Long = 0L,
    val winc: Long = 0L,
    val binc: Long = 0L,
    val status: String = "started",
    val winner: String? = null,
) {
    /** Terminal statuses per `https://lichess.org/api#tag/Board/operation/boardGameStream`. */
    val isTerminal: Boolean get() = status in TERMINAL_STATUSES

    /** Shared constants and helpers for GameState. */
    companion object {
        private val TERMINAL_STATUSES = setOf(
            "aborted", "mate", "resign", "stalemate", "timeout", "draw", "outoftime", "cheat",
            "noStart", "variantEnd",
        )
    }
}

/** Decoded game-stream line: full snapshot first, deltas after. Unknown → [Ignored]. */
sealed interface LichessGameUpdate {
    /** Immutable value describing full; carries game. */
    data class Full(val game: GameFull) : LichessGameUpdate
    /** Immutable value describing state; carries state. */
    data class State(val state: GameState) : LichessGameUpdate
    /** Singleton holding ignored state and operations. */
    data object Ignored : LichessGameUpdate
}

private const val GAME_FULL_TYPE = "gameFull"
private const val GAME_STATE_TYPE = "gameState"

/** Decodes one NDJSON line by its `type` field; never throws on unknown shapes. */
fun decodeGameLine(element: JsonElement, json: Json): LichessGameUpdate {
    val type = (element as? kotlinx.serialization.json.JsonObject)?.get("type")
        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
        ?.takeIf { it.isString }?.content ?: ""
    return when (type) {
        GAME_FULL_TYPE -> LichessGameUpdate.Full(json.decodeFromJsonElement(GameFull.serializer(), element))
        GAME_STATE_TYPE -> LichessGameUpdate.State(json.decodeFromJsonElement(GameState.serializer(), element))
        else -> LichessGameUpdate.Ignored
    }
}

/** Time control for seek/challenge: minutes + increment seconds (Lichess `clock.limit/inc`). */
data class LichessTimeControl(val minutes: Int, val incrementSeconds: Int) {
    val label: String get() = "$minutes+$incrementSeconds"

    /** Shared constants and helpers for LichessTimeControl. */
    companion object {
        val BULLET = LichessTimeControl(1, 0)
        val BLITZ_3_2 = LichessTimeControl(3, 2)
        val RAPID_5_3 = LichessTimeControl(5, 3)
        val RAPID_10_0 = LichessTimeControl(10, 0)

        val PRESETS = listOf(BULLET, BLITZ_3_2, RAPID_5_3, RAPID_10_0)
    }
}
