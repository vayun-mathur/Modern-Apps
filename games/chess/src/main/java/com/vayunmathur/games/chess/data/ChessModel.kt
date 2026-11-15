package com.vayunmathur.games.chess.data
import kotlin.math.abs
import com.vayunmathur.games.chess.R

internal const val BOARD_SIZE = 8
internal const val WHITE_HOME_ROW = 7
internal const val BLACK_HOME_ROW = 0
internal const val KING_HOME_FILE = 4
internal const val ROOK_KINGSIDE_FILE = 7
internal const val ROOK_QUEENSIDE_FILE = 0
internal const val PAWN_DOUBLE_STEP = 2
internal const val CASTLE_FILE_DISTANCE = 2
internal const val BLACK_PROMOTION_ROW = 7
internal const val WHITE_PROMOTION_ROW = 0
internal const val PAWN_START_ROW_WHITE = 6
internal const val PAWN_START_ROW_BLACK = 1
internal const val WHITE_EP_ROW = 4
internal const val BLACK_EP_ROW = 3
internal const val SQUARE_COLOR_MOD = 2

/** Kinds of piece type. */
enum class PieceType(val resID: Int) {
    KING(R.drawable.chess_king_2_fill1_24px),
    QUEEN(R.drawable.chess_queen_fill1_24px),
    ROOK(R.drawable.chess_rook_fill1_24px),
    BISHOP(R.drawable.chess_bishop_fill1_24px),
    KNIGHT(R.drawable.chess_knight_fill1_24px),
    PAWN(R.drawable.chess_pawn_fill1_24px)
}

/** Kinds of piece color. */
enum class PieceColor { WHITE, BLACK }

val PieceColor.opposite get() = if (this == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE

/** Immutable value describing piece; carries type, color, hasMoved. */
data class Piece(val type: PieceType, val color: PieceColor, val hasMoved: Boolean = false)

/** Immutable value describing position; carries row, col. */
data class Position(val row: Int, val col: Int)

/** Castling availability for a position, as encoded in a FEN's castling field. */
data class CastlingRights(
    val whiteKing: Boolean = false,
    val whiteQueen: Boolean = false,
    val blackKing: Boolean = false,
    val blackQueen: Boolean = false
)

private val PieceType.notationLetter: String get() = when (this) {
    PieceType.KING -> "K"; PieceType.QUEEN -> "Q"; PieceType.ROOK -> "R"
    PieceType.BISHOP -> "B"; PieceType.KNIGHT -> "N"; PieceType.PAWN -> ""
}

/** Immutable value describing move. */
data class Move(
    val start: Position,
    val end: Position,
    val piece: Piece,
    val capturedPiece: Piece? = null,
    var promotedTo: PieceType? = null,
    val isCheck: Boolean = false,
    val isCheckmate: Boolean = false,
    val isCastling: Boolean = false,
    val ambiguity: String = ""
) {
    override fun toString(): String {
        if (isCastling) return if (end.col > start.col) "O-O" else "O-O-O"

        return buildString {
            if (piece.type != PieceType.PAWN) {
                append(piece.type.notationLetter)
                append(ambiguity)
            }
            if (capturedPiece != null) {
                if (piece.type == PieceType.PAWN) append(getFileChar(start.col))
                append("x")
            }
            append(getFileChar(end.col))
            append(getRankChar(end.row))
            promotedTo?.let { append("=").append(it.notationLetter) }
            when {
                isCheckmate -> append("#")
                isCheck -> append("+")
            }
        }
    }
}

/** Immutable value describing board. */
data class Board(
    val pieces: List<List<Piece?>>,
    val capturedByWhite: List<Piece> = emptyList(),
    val capturedByBlack: List<Piece> = emptyList(),
    val lastMove: Move? = null,
    val promotionPosition: Position? = null,
    val moves: List<Move> = emptyList()
) {

    fun movePiece(start: Position, end: Position, promoteTo: PieceType? = null): Board {
        val movingPiece = pieces[start.row][start.col]
            ?: throw IllegalStateException("No piece at start position")

        val ambiguity = calculateAmbiguity(start, end, movingPiece)
        val capturedPiece = capturedFor(start, end, movingPiece)
        val newPieces = movedGrid(start, end, movingPiece, promoteTo)

        val newCapturedWhite = capturedByWhite +
            listOfNotNull(capturedPiece?.takeIf { it.color == PieceColor.BLACK })
        val newCapturedBlack = capturedByBlack +
            listOfNotNull(capturedPiece?.takeIf { it.color == PieceColor.WHITE })

        val afterBoard = Board(newPieces.map { it.toList() })
        val opponentColor = movingPiece.color.opposite
        val isCheck = afterBoard.isKingInCheck(opponentColor)
        val isCheckmate = afterBoard.isCheckmate(opponentColor)

        val fullMove = Move(
            start = start, end = end, piece = movingPiece,
            capturedPiece = capturedPiece, promotedTo = promoteTo,
            isCheck = isCheck, isCheckmate = isCheckmate,
            isCastling = isCastleMove(movingPiece, start, end),
            ambiguity = ambiguity
        )

        return Board(
            pieces = newPieces.map { it.toList() },
            capturedByWhite = newCapturedWhite,
            capturedByBlack = newCapturedBlack,
            lastMove = fullMove,
            promotionPosition = promotionSquare(movingPiece, end, promoteTo),
            moves = moves + fullMove
        )
    }

    private fun isCastleMove(movingPiece: Piece, start: Position, end: Position): Boolean =
        movingPiece.type == PieceType.KING && abs(start.col - end.col) == CASTLE_FILE_DISTANCE

    private fun promotionSquare(movingPiece: Piece, end: Position, promoteTo: PieceType?): Position? {
        if (promoteTo != null) return null
        return if (isPromotionSquare(movingPiece, end)) end else null
    }

    fun promotePawn(position: Position, to: PieceType): Board {
        val newPieces = pieces.map { it.toMutableList() }.toMutableList()
        val piece = newPieces[position.row][position.col]!!
        newPieces[position.row][position.col] = piece.copy(type = to)

        val tempBoard = copy(pieces = newPieces.map { it.toList() })
        val opponentColor = piece.color.opposite
        val isCheck = tempBoard.isKingInCheck(opponentColor)
        val isCheckmate = tempBoard.isCheckmate(opponentColor)

        val updatedMoves = moves.toMutableList()
        var updatedLastMove = lastMove
        if (updatedMoves.isNotEmpty()) {
            val promoted = updatedMoves.removeAt(updatedMoves.lastIndex)
            updatedLastMove = promoted.copy(promotedTo = to, isCheck = isCheck, isCheckmate = isCheckmate)
            updatedMoves.add(updatedLastMove)
        }

        return copy(
            pieces = newPieces.map { it.toList() },
            promotionPosition = null,
            moves = updatedMoves,
            lastMove = updatedLastMove
        )
    }

    fun isValidMove(start: Position, end: Position): Boolean {
        val piece = pieces[start.row][start.col] ?: return false
        if (!isValidMoveIgnoringCheck(start, end)) return false
        return !applyMoveInternal(start, end).isKingInCheck(piece.color)
    }

    /**
     * Legality of a move ignoring whether it leaves the mover's own king in check
     * (i.e. pseudo-legal). Used by the Learn feature to model Lichess's antichess-style
     * kingless lesson boards and its "offer illegal move" levels.
     */
    fun isPseudoLegalMove(start: Position, end: Position): Boolean =
        isValidMoveIgnoringCheck(start, end)

    /** Total number of kings on the board (both colors). */
    fun kingCount(): Int = pieces.sumOf { row -> row.count { it?.type == PieceType.KING } }

    fun isKingInCheck(kingColor: PieceColor): Boolean {
        val kingPos = findKing(kingColor) ?: return false
        return pieces.flatMapIndexed { row, cols ->
            cols.mapIndexedNotNull { col, piece ->
                if (piece != null && piece.color != kingColor) Position(row, col) else null
            }
        }.any { isValidMoveIgnoringCheck(it, kingPos) }
    }

    /** True if [color] has at least one legal move (move that doesn't leave its own king in check). */
    fun hasLegalMoves(color: PieceColor): Boolean {
        var any = false
        walkLegalMoves(color) { _, _ ->
            any = true
            false
        }
        return any
    }

    /**
     * Every legal move [color] can play, with the four promotion choices expanded.
     *
     * The engine's move list and the mate/stalemate test are the same generator, so the rules
     * cannot disagree with themselves — [hasLegalMoves] is this with an early exit.
     *
     * Promotions are the one thing [isValidMove] cannot express: it validates a pawn's step to
     * the last rank without saying what it becomes, and [movePiece] handles the choice
     * out-of-band through [promotionPosition]. An engine has to choose up front, so a promoting
     * step appears here four times, once per [PROMOTION_CHOICES] entry.
     */
    fun legalMoves(color: PieceColor): List<Move> {
        val moves = mutableListOf<Move>()
        walkLegalMoves(color) { start, end ->
            val piece = pieces[start.row][start.col] ?: return@walkLegalMoves true
            // En passant captures a pawn that is not on the destination square, so the captured
            // piece is read the way `movePiece` reads it rather than off `end`.
            val captured = if (piece.type == PieceType.PAWN && isEnPassant(start, end)) {
                pieces[enPassantCaptureRow(piece.color, end.row)][end.col]
            } else {
                pieces[end.row][end.col]
            }
            if (isPromotionSquare(piece, end)) {
                for (promotion in PROMOTION_CHOICES) {
                    moves.add(Move(start, end, piece, captured, promotedTo = promotion))
                }
            } else {
                moves.add(Move(start, end, piece, captured))
            }
            true
        }
        return moves
    }

    fun isCheckmate(kingColor: PieceColor): Boolean =
        isKingInCheck(kingColor) && !hasLegalMoves(kingColor)

    /** Side to move ([color]) is not in check but has no legal move → draw by stalemate. */
    fun isStalemate(color: PieceColor): Boolean =
        !isKingInCheck(color) && !hasLegalMoves(color)

    /**
     * Draw by insufficient mating material: K vs K, K+minor vs K, and K+B vs K+B with the
     * bishops on same-colour squares. Anything with a pawn/rook/queen, or that could still
     * force mate (e.g. two bishops, bishop+knight), is not auto-drawn.
     */
    fun isInsufficientMaterial(): Boolean {
        val all = pieces.flatten().filterNotNull()
        if (hasMatingMajor(all)) return false
        val minors = all.filter { it.type == PieceType.BISHOP || it.type == PieceType.KNIGHT }
        if (minors.size <= 1) return true // K vs K, or K + single minor vs K
        if (minors.any { it.type == PieceType.KNIGHT }) return false
        return bishopsShareSquareColor()
    }

    private fun hasMatingMajor(all: List<Piece>): Boolean {
        return all.any {
            it.type == PieceType.PAWN || it.type == PieceType.ROOK || it.type == PieceType.QUEEN
        }
    }

    private fun bishopsShareSquareColor(): Boolean {
        // only bishops left: drawn iff every bishop sits on the same square colour
        val squareColors = pieces.flatMapIndexed { r, cols ->
            cols.mapIndexedNotNull { c, p ->
                if (p?.type == PieceType.BISHOP) (r + c) % SQUARE_COLOR_MOD else null
            }
        }.toSet()
        return squareColors.size == 1
    }

    /** Shared constants and helpers for Board. */
    companion object {
        /**
         * What a pawn may become, in the order Maia3's move vocabulary lists them.
         *
         * The order is load-bearing for `MaiaEngine.moveIndex`, which turns a promotion into
         * `4096 + fromFile * 32 + toFile * 4 + PROMOTION_CHOICES.indexOf(piece)`. Reordering it
         * would silently score every promotion as a different piece.
         */
        val PROMOTION_CHOICES = listOf(
            PieceType.QUEEN,
            PieceType.ROOK,
            PieceType.BISHOP,
            PieceType.KNIGHT,
        )

        val initialState = Board(
            pieces = listOf(
                listOf(
                    Piece(PieceType.ROOK, PieceColor.BLACK), Piece(PieceType.KNIGHT, PieceColor.BLACK),
                    Piece(PieceType.BISHOP, PieceColor.BLACK), Piece(PieceType.QUEEN, PieceColor.BLACK),
                    Piece(PieceType.KING, PieceColor.BLACK), Piece(PieceType.BISHOP, PieceColor.BLACK),
                    Piece(PieceType.KNIGHT, PieceColor.BLACK), Piece(PieceType.ROOK, PieceColor.BLACK)
                ),
                List(8) { Piece(PieceType.PAWN, PieceColor.BLACK) },
                List(8) { null }, List(8) { null }, List(8) { null }, List(8) { null },
                List(8) { Piece(PieceType.PAWN, PieceColor.WHITE) },
                listOf(
                    Piece(PieceType.ROOK, PieceColor.WHITE), Piece(PieceType.KNIGHT, PieceColor.WHITE),
                    Piece(PieceType.BISHOP, PieceColor.WHITE), Piece(PieceType.QUEEN, PieceColor.WHITE),
                    Piece(PieceType.KING, PieceColor.WHITE), Piece(PieceType.BISHOP, PieceColor.WHITE),
                    Piece(PieceType.KNIGHT, PieceColor.WHITE), Piece(PieceType.ROOK, PieceColor.WHITE)
                ),
            )
        )

        private fun pieceFromFenChar(ch: Char): Piece {
            val color = if (ch.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK
            val type = when (ch.lowercaseChar()) {
                'k' -> PieceType.KING; 'q' -> PieceType.QUEEN; 'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP; 'n' -> PieceType.KNIGHT; 'p' -> PieceType.PAWN
                else -> throw IllegalArgumentException("Invalid FEN piece char: $ch")
            }
            return Piece(type, color)
        }

        /**
         * Parses a FEN string into a puzzle [Board]. Only the placement, side-to-move,
         * castling, and en-passant fields are used (halfmove/fullmove clocks are
         * ignored). See [fromPuzzle] for how turn/castling/en-passant are reconstructed.
         */
        fun fromFen(fen: String): Board = parseFen(fen, ::pieceFromFenChar, ::fromPuzzle)

        /**
         * Builds a puzzle [Board] from a raw piece grid plus the side to move,
         * castling rights, and en-passant target. Shared by [fromFen] and the binary
         * puzzle decoder so both reconstruct positions identically.
         *
         * The Board rules model has no explicit turn/castling/en-passant state; it
         * infers them from [Piece.hasMoved] and [lastMove]. So we: set `hasMoved=false`
         * only on the king/rook that still hold a castling right (everything else
         * `hasMoved=true`; pawn double-steps key off the start rank, not `hasMoved`),
         * and seed a `lastMove` that encodes the side to move (and, when an en-passant
         * target exists, a pawn double-step so `isEnPassant` accepts the capture).
         */
        fun fromPuzzle(
            rawPieces: List<List<Piece?>>,
            sideToMove: PieceColor,
            castling: CastlingRights,
            epSquare: Position?
        ): Board = buildPuzzleBoard(rawPieces, sideToMove, castling, epSquare)
    }
}
