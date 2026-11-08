package com.vayunmathur.games.chess.util

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.opposite
import com.vayunmathur.library.ml.MaiaHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.random.Random

/**
 * How strong the AI plays, as a rating the model is asked to imitate.
 *
 * Maia3 takes strength as an **input** rather than as a search handicap, so these are Elo
 * ratings and not depth or skill settings. A level plays like a human of that rating: it makes
 * the mistakes that rating makes, rather than playing well and then discarding a move.
 *
 * Ratings are from Lichess blitz, which is what the model was trained on. `GRANDMASTER` at 2500
 * is near the top of that distribution; asking for much more extrapolates past what the model
 * has seen rather than getting stronger.
 *
 * Sampling is deliberately not argmax — the upstream `maia3-5m` launcher runs at temperature 0,
 * which plays the identical game from the identical position every time. A little temperature
 * with nucleus sampling keeps the strength while varying the game, and it narrows as the level
 * rises because a stronger player has fewer moves worth considering.
 *
 * **Four entries in ascending order**, and that is a contract with two places:
 * `MainActivity`'s picker zips them positionally against four localized labels, and its
 * `win_vs_ai_hard` achievement gate compares ordinals against [ADVANCED].
 *
 * Declared here as a top-level enum rather than inside [MaiaEngine] on purpose: the store
 * listing screenshots render `NewGameDialog`, which reads [entries], on a JVM with no device.
 * Reaching it must not pull in a class whose initialiser loads a `.so`.
 */
enum class Difficulty(val elo: Int, val temperature: Float, val topP: Float) {
    BEGINNER(ELO_BEGINNER, TEMP_BEGINNER, TOP_P),
    INTERMEDIATE(ELO_INTERMEDIATE, TEMP_INTERMEDIATE, TOP_P),
    ADVANCED(ELO_ADVANCED, TEMP_ADVANCED, TOP_P),
    GRANDMASTER(ELO_GRANDMASTER, TEMP_GRANDMASTER, TOP_P);
}

private const val ELO_BEGINNER = 1100
private const val ELO_INTERMEDIATE = 1500
private const val ELO_ADVANCED = 1900
private const val ELO_GRANDMASTER = 2500
private const val TEMP_BEGINNER = 0.5f
private const val TEMP_INTERMEDIATE = 0.4f
private const val TEMP_ADVANCED = 0.35f
private const val TEMP_GRANDMASTER = 0.3f
private const val TOP_P = 0.95f

/**
 * The chess AI: Maia3-5M on `:library:ml`'s Vulkan runtime.
 *
 * One forward pass per move and no search. It replaces Stockfish, which was an 86 MB NNUE and a
 * depth-8 tree behind a JitPack AAR driven over UCI text through two channels; none of that
 * survives, including the UCI, which was only ever Stockfish's transport.
 *
 * # What a move costs
 *
 * Encode the board to 12 planes, one inference, mask 4352 logits to the legal moves, sample.
 * The mask is what makes the model safe to use: it scores every from-to pair on the board,
 * including the ~4000 that are not moves, so the legal list is not an optimisation.
 *
 * # Black is mirrored
 *
 * The model only ever sees a position from the mover's side: when black is to move the board is
 * flipped vertically and the colours are swapped. That is also why the vocabulary has no black
 * promotions at all — every promotion is rank 7 to rank 8. [moveIndex] does the flip in one
 * place, for both squares of a move, so encoding and scoring cannot disagree about it.
 *
 * # The lock
 *
 * [MaiaHandle] is not thread-safe, so the mutex covers the whole of [bestMove] and the lazy
 * load inside it. Modelled on `NllbTranslator`, including [close] being non-suspending because
 * its only caller is `ViewModel.onCleared`.
 */
class MaiaEngine(private val context: Context) {

    private val lock = Mutex()
    private var model: MaiaHandle? = null
    private var attempts = 0
    private var closed = false
    private val random = Random.Default

    /**
     * Whether the AI can play at all, loading the model to find out.
     *
     * False when the device has no `libmodelrunner.so` for its ABI, or no Vulkan device with
     * fp16 compute. The caller hides the "play against the AI" option rather than offering a
     * mode that cannot move.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.Default) {
        lock.withLock { ensure() != null }
    }

    /**
     * The move the AI plays for [turn] on [board], or null if it has none or the model failed.
     *
     * [turn] is the side to move and comes from the UI's own state, not from the board: the
     * rules model infers the turn from its last move, and the puzzle path seeds that with a
     * synthetic zero-length king move.
     *
     * Runs on [Dispatchers.Default] because a forward pass is synchronous and would otherwise
     * block whichever thread called it.
     */
    suspend fun bestMove(board: Board, turn: PieceColor, difficulty: Difficulty): Move? =
        withContext(Dispatchers.Default) {
            val legal = board.legalMoves(turn)
            if (legal.isEmpty()) return@withContext null
            lock.withLock {
                val handle = ensure() ?: return@withContext null
                val logits = try {
                    handle.logits(encodePlanes(board, turn), difficulty.elo, difficulty.elo)
                } catch (expected: IllegalStateException) {
                    Log.error(TAG, "inference failed", expected)
                    null
                } catch (expected: IllegalArgumentException) {
                    Log.error(TAG, "inference failed", expected)
                    null
                } ?: return@withContext null
                choose(legal, logits, turn, difficulty)
            }
        }

    /**
     * Free the model. Terminal: nothing loads again afterwards, and it is idempotent.
     *
     * Deliberately not `suspend`, because the only caller is `ViewModel.onCleared`, which is
     * not. It therefore does not take [lock], which is safe only because `viewModelScope` is
     * cancelled before `onCleared` runs and the UI cannot ask for another move afterwards.
     */
    fun close() {
        closed = true
        model?.close()
        model = null
    }

    /**
     * The loaded model, loading it on first use.
     *
     * Capped rather than latched on the first failure, for the same reason `NllbTranslator`
     * caps: a transient failure should not be permanent. There is no download here, so the cap
     * is low — a bundled asset that failed to open once will fail again.
     */
    private fun ensure(): MaiaHandle? {
        model?.let { return it }
        if (closed || attempts >= MAX_ATTEMPTS) return null
        attempts++
        val handle = MaiaHandle.inAssets(context.assets)
        if (!handle.isAvailable) {
            handle.close()
            return null
        }
        model = handle
        return handle
    }

    /**
     * Pick one of [legal] by nucleus sampling its logits at [Difficulty.temperature].
     *
     * The candidate set is the legal moves, scored by looking each one up in the vocabulary —
     * rather than taking the vector's argmax and decoding it, which would have to cope with the
     * ~4000 entries that are not moves in this position.
     */
    private fun choose(
        legal: List<Move>,
        logits: FloatArray,
        turn: PieceColor,
        difficulty: Difficulty,
    ): Move {
        val scored = legal.map { move ->
            move to (logits.getOrNull(moveIndex(move, turn)) ?: Float.NEGATIVE_INFINITY)
        }.sortedByDescending { it.second }

        val top = scored.first().second
        if (!top.isFinite()) return scored.first().first
        val temperature = difficulty.temperature.coerceAtLeast(MIN_TEMPERATURE)
        // Subtract the maximum before exponentiating, so a large logit cannot overflow.
        val weights = scored.map { exp(((it.second - top) / temperature).toDouble()) }
        val total = weights.sum()
        if (total <= 0.0 || !total.isFinite()) return scored.first().first

        // Nucleus: keep the shortest prefix of the sorted moves whose probability reaches topP,
        // so a blunder three orders of magnitude down cannot be sampled even at temperature.
        var mass = 0.0
        var keep = 0
        while (keep < weights.size) {
            mass += weights[keep] / total
            keep++
            if (mass >= difficulty.topP) break
        }

        var target = random.nextDouble() * mass * total
        for (index in 0 until keep) {
            target -= weights[index]
            if (target <= 0.0) return scored[index].first
        }
        return scored[keep - 1].first
    }

    companion object {
        private const val TAG = "MaiaEngine"

        /** One retry. A bundled asset that failed to open once will fail again. */
        private const val MAX_ATTEMPTS = 2

        /** A floor, so a zero temperature is argmax rather than a division by zero. */
        private const val MIN_TEMPERATURE = 1e-3f

        private const val BOARD_RANKS = 8
        private const val BOARD_FILES = 8
        private const val LAST_RANK = 7
        private const val OPPONENT_PLANE_OFFSET = 6
        private const val OCCUPIED = 1f
        private const val PROMOTION_BASE = 4096
        private const val FROM_FILE_STRIDE = 32
        private const val TO_FILE_STRIDE = 4

        /**
         * The plane a piece type occupies, in `maia3/dataset.py`'s `PIECE_MAP` order.
         *
         * Planes 0..5 are the mover's pieces and 6..11 the opponent's, both in this order. Note
         * that it is **not** [PieceType]'s own declaration order, which starts at the king.
         */
        private val PLANE_ORDER = listOf(
            PieceType.PAWN,
            PieceType.KNIGHT,
            PieceType.BISHOP,
            PieceType.ROOK,
            PieceType.QUEEN,
            PieceType.KING,
        )

        /**
         * [board] as the 12 planes the model reads, from [turn]'s side.
         *
         * `12 * 64` floats, plane-major, square `rank * 8 + file` so a1 is 0 and h8 is 63. When
         * black is to move the board is mirrored vertically **and** the colours are swapped, so
         * the result is indistinguishable from the same position with white to move — which is
         * the whole of why one set of six "own piece" planes is enough.
         */
        fun encodePlanes(board: Board, turn: PieceColor): FloatArray {
            val planes = FloatArray(MaiaHandle.PLANE_COUNT * MaiaHandle.SQUARES)
            for (row in 0 until BOARD_RANKS) {
                for (col in 0 until BOARD_FILES) {
                    val piece = board.pieces[row][col] ?: continue
                    val colour = if (turn == PieceColor.BLACK) piece.color.opposite else piece.color
                    val plane = PLANE_ORDER.indexOf(piece.type) +
                        if (colour == PieceColor.BLACK) OPPONENT_PLANE_OFFSET else 0
                    val sq = squareOf(Position(row, col), turn)
                    planes[plane * MaiaHandle.SQUARES + sq] = OCCUPIED
                }
            }
            return planes
        }

        /**
         * Where [move] sits in the 4352-entry vocabulary, from [turn]'s side.
         *
         * `from * 64 + to` for an ordinary move; for a promotion,
         * `4096 + fromFile * 32 + toFile * 4 + piece` with the piece index from
         * [Board.PROMOTION_CHOICES]. Promotions carry no rank because they are always rank 7 to
         * rank 8 once the board is from the mover's side.
         */
        fun moveIndex(move: Move, turn: PieceColor): Int {
            val from = squareOf(move.start, turn)
            val to = squareOf(move.end, turn)
            val promotion = move.promotedTo ?: return from * MaiaHandle.SQUARES + to
            val piece = Board.PROMOTION_CHOICES.indexOf(promotion)
            return PROMOTION_BASE + (from % BOARD_FILES) * FROM_FILE_STRIDE +
                (to % BOARD_FILES) * TO_FILE_STRIDE + piece
        }

        /**
         * [position] as a model square index, mirrored when black is to move.
         *
         * The rules model has row 0 at rank 8; the model numbers squares `rank * 8 + file` from
         * a1. Both conversions are here so nothing else has to know either convention.
         */
        private fun squareOf(position: Position, turn: PieceColor): Int {
            val rank = LAST_RANK - position.row
            val fromMoversSide = if (turn == PieceColor.BLACK) LAST_RANK - rank else rank
            return fromMoversSide * BOARD_FILES + position.col
        }
    }
}
