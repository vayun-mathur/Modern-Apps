package com.vayunmathur.games.chess.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A hint arrow (orig->dest) or circle (orig only), matching Lichess's learn shapes. */
@Serializable
data class LearnShape(
    val orig: String,
    val dest: String? = null,
    val brush: String = "green"
)

/**
 * One interactive lesson level. Fields mirror the Lichess `ui/learn` level
 * blueprint (see scripts/chess/extract_learn.py). [goalType] selects the success
 * mechanic evaluated by [com.vayunmathur.games.chess.util.LearnViewModel].
 */
@Serializable
data class LearnLevel(
    val goal: String,
    val fen: String,
    val color: String = "white",
    val goalType: String,
    val nbMoves: Int = 1,
    val apples: String? = null,
    val captures: Int? = null,
    val detectCapture: String? = null,
    val emptyApples: Boolean = false,
    val offerIllegalMove: Boolean = false,
    val showPieceValues: Boolean = false,
    val pointsForCapture: Boolean = false,
    val explainPromotion: Boolean = false,
    val nextButton: Boolean = false,
    val captureColor: String? = null,
    val n: Int? = null,
    val castleSide: String? = null,
    val scenario: List<String> = emptyList(),
    val shapes: List<LearnShape> = emptyList(),
    val failIfWhitePawnOn: List<String> = emptyList(),
    val failIfPieceOffPath: List<String> = emptyList()
) {
    val playerColor: PieceColor get() = if (color == "black") PieceColor.BLACK else PieceColor.WHITE
    val appleSquares: List<Position> get() = apples
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?.map { square(it) }
        ?: emptyList()
}

/** Immutable value describing learn stage. */
@Serializable
data class LearnStage(
    val id: Int = 0,
    val key: String,
    val title: String,
    val subtitle: String,
    val intro: String,
    val complete: String,
    val levels: List<LearnLevel>
)

/** Immutable value describing learn category; carries key, name, stages. */
@Serializable
data class LearnCategory(val key: String, val name: String, val stages: List<LearnStage>)

/** Immutable value describing learn data; carries categories. */
@Serializable
data class LearnData(val categories: List<LearnCategory>)

/** Parses an algebraic square like "e4" into a board [Position] (row 0 = rank 8). */
fun square(s: String): Position = Position(BOARD_RANKS - (s[1] - '0'), s[0] - 'a')

private const val BOARD_RANKS = 8

/** Parses a UCI move like "e2e4" or "e7e8q" into (from, to, promotion). */
fun parseUci(uci: String): Triple<Position, Position, PieceType?> {
    val from = square(uci.substring(0, 2))
    val to = square(uci.substring(2, 4))
    val promo = if (uci.length >= 5) when (uci[4]) {
        'q' -> PieceType.QUEEN; 'r' -> PieceType.ROOK; 'b' -> PieceType.BISHOP; 'n' -> PieceType.KNIGHT
        else -> null
    } else null
    return Triple(from, to, promo)
}

/**
 * Loads the bundled `learn.json` (derived from Lichess's AGPL learn module) and
 * builds lesson boards. Mirrors [PuzzleRepository]'s idempotent, IO-loaded pattern.
 */
object LearnRepository {
    private const val ASSET_NAME = "learn.json"
    private val json = Json { ignoreUnknownKeys = true }

    private var data: LearnData? = null

    val categories: List<LearnCategory> get() = data?.categories ?: emptyList()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (data != null) return
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        data = json.decodeFromString(LearnData.serializer(), text)
    }

    fun stage(categoryKey: String, stageKey: String): LearnStage? =
        categories.firstOrNull { it.key == categoryKey }?.stages?.firstOrNull { it.key == stageKey }

    /** The (categoryKey, stage) that follows [stageKey] across all categories, or null if last. */
    fun nextStage(stageKey: String): Pair<String, LearnStage>? {
        val flat = categories.flatMap { c -> c.stages.map { c.key to it } }
        val idx = flat.indexOfFirst { it.second.key == stageKey }
        return if (idx >= 0 && idx + 1 < flat.size) flat[idx + 1] else null
    }

    /**
     * Builds the starting [Board] for [level]. Apple target squares are placed as
     * enemy pawns (so a lesson piece "collects" a star by capturing it, and pawns
     * can capture diagonally onto them) — except on [LearnLevel.emptyApples] levels,
     * where apples are plain empty target squares.
     */
    fun buildBoard(level: LearnLevel): Board {
        val base = Board.fromFen(level.fen)
        if (level.emptyApples || level.appleSquares.isEmpty()) return base

        val enemyColor = level.playerColor.opposite
        val grid = base.pieces.map { it.toMutableList() }.toMutableList()
        for (pos in level.appleSquares) {
            if (grid[pos.row][pos.col] == null) {
                grid[pos.row][pos.col] = Piece(PieceType.PAWN, enemyColor, hasMoved = true)
            }
        }
        return base.copy(pieces = grid.map { it.toList() })
    }
}
