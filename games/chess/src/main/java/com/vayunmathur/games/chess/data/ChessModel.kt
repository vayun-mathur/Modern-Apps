package com.vayunmathur.games.chess.data
import kotlin.math.abs
import com.vayunmathur.games.chess.R

enum class PieceType(val resID: Int) {
    KING(R.drawable.chess_king_2_fill1_24px),
    QUEEN(R.drawable.chess_queen_fill1_24px),
    ROOK(R.drawable.chess_rook_fill1_24px),
    BISHOP(R.drawable.chess_bishop_fill1_24px),
    KNIGHT(R.drawable.chess_knight_fill1_24px),
    PAWN(R.drawable.chess_pawn_fill1_24px)
}

enum class PieceColor { WHITE, BLACK }

val PieceColor.opposite get() = if (this == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE

data class Piece(val type: PieceType, val color: PieceColor, val hasMoved: Boolean = false)

data class Position(val row: Int, val col: Int)

private fun getFileChar(col: Int): Char = 'a' + col
private fun getRankChar(row: Int): Char = '8' - row

private val PieceType.notationLetter: String get() = when (this) {
    PieceType.KING -> "K"; PieceType.QUEEN -> "Q"; PieceType.ROOK -> "R"
    PieceType.BISHOP -> "B"; PieceType.KNIGHT -> "N"; PieceType.PAWN -> ""
}

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
        var capturedPiece = pieces[end.row][end.col]
        val isEnPassantMove = movingPiece.type == PieceType.PAWN && isEnPassant(start, end)

        if (isEnPassantMove) {
            capturedPiece = pieces[enPassantCaptureRow(movingPiece.color, end.row)][end.col]
        }

        val newPieces = pieces.map { it.toMutableList() }.toMutableList()

        var isCastlingMove = false
        if (movingPiece.type == PieceType.KING && abs(start.col - end.col) == 2) {
            isCastlingMove = true
            val rookStartCol = if (end.col > start.col) 7 else 0
            val rookEndCol = if (end.col > start.col) end.col - 1 else end.col + 1
            newPieces[start.row][rookEndCol] = newPieces[start.row][rookStartCol]?.copy(hasMoved = true)
            newPieces[start.row][rookStartCol] = null
        }

        if (isEnPassantMove) {
            newPieces[enPassantCaptureRow(movingPiece.color, end.row)][end.col] = null
        }

        newPieces[end.row][end.col] = movingPiece.copy(type = promoteTo ?: movingPiece.type, hasMoved = true)
        newPieces[start.row][start.col] = null

        val newCapturedWhite = if (capturedPiece?.color == PieceColor.BLACK) capturedByWhite + capturedPiece else capturedByWhite
        val newCapturedBlack = if (capturedPiece?.color == PieceColor.WHITE) capturedByBlack + capturedPiece else capturedByBlack

        val tempNextBoard = Board(newPieces.map { it.toList() })
        val opponentColor = movingPiece.color.opposite
        val isCheck = tempNextBoard.isKingInCheck(opponentColor)
        val isCheckmate = tempNextBoard.isCheckmate(opponentColor)

        val fullMove = Move(
            start = start, end = end, piece = movingPiece,
            capturedPiece = capturedPiece, promotedTo = promoteTo,
            isCheck = isCheck, isCheckmate = isCheckmate,
            isCastling = isCastlingMove, ambiguity = ambiguity
        )

        return Board(
            pieces = newPieces.map { it.toList() },
            capturedByWhite = newCapturedWhite,
            capturedByBlack = newCapturedBlack,
            lastMove = fullMove,
            promotionPosition = if (promoteTo == null && isPromotionSquare(movingPiece, end)) end else null,
            moves = moves + fullMove
        )
    }

    private fun movePieceInternal(start: Position, end: Position): Board {
        val newPieces = pieces.map { it.toMutableList() }.toMutableList()
        val piece = newPieces[start.row][start.col] ?: return this

        if (piece.type == PieceType.PAWN && isEnPassant(start, end)) {
            newPieces[enPassantCaptureRow(piece.color, end.row)][end.col] = null
        }

        if (piece.type == PieceType.KING && abs(start.col - end.col) == 2) {
            val rookStartCol = if (end.col > start.col) 7 else 0
            val rookEndCol = if (end.col > start.col) end.col - 1 else end.col + 1
            newPieces[start.row][rookEndCol] = newPieces[start.row][rookStartCol]?.copy(hasMoved = true)
            newPieces[start.row][rookStartCol] = null
        }

        newPieces[end.row][end.col] = piece.copy(hasMoved = true)
        newPieces[start.row][start.col] = null

        return copy(pieces = newPieces.map { it.toList() }, lastMove = Move(start, end, piece))
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
            updatedLastMove = updatedMoves.removeLast().copy(promotedTo = to, isCheck = isCheck, isCheckmate = isCheckmate)
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
        return !movePieceInternal(start, end).isKingInCheck(piece.color)
    }

    private fun isValidMoveIgnoringCheck(start: Position, end: Position): Boolean {
        val piece = pieces[start.row][start.col] ?: return false
        val targetPiece = pieces[end.row][end.col]
        if (targetPiece != null && targetPiece.color == piece.color) return false

        return when (piece.type) {
            PieceType.PAWN -> isValidPawnMove(start, end, piece.color)
            PieceType.ROOK -> isValidRookMove(start, end)
            PieceType.KNIGHT -> isValidKnightMove(start, end)
            PieceType.BISHOP -> isValidBishopMove(start, end)
            PieceType.QUEEN -> isValidRookMove(start, end) || isValidBishopMove(start, end)
            PieceType.KING -> isValidKingMove(start, end, piece)
        }
    }

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
        return pieces.flatMapIndexed { row, cols ->
            cols.mapIndexedNotNull { col, piece ->
                if (piece != null && piece.color == color) Position(row, col) else null
            }
        }.any { pos ->
            (0..7).any { i -> (0..7).any { j -> isValidMove(pos, Position(i, j)) } }
        }
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
        if (all.any { it.type == PieceType.PAWN || it.type == PieceType.ROOK || it.type == PieceType.QUEEN }) {
            return false
        }
        val minors = all.filter { it.type == PieceType.BISHOP || it.type == PieceType.KNIGHT }
        return when (minors.size) {
            0, 1 -> true // K vs K, or K + single minor vs K
            else -> {
                if (minors.any { it.type == PieceType.KNIGHT }) return false
                // only bishops left: drawn iff every bishop sits on the same square colour
                val squareColors = pieces.flatMapIndexed { r, cols ->
                    cols.mapIndexedNotNull { c, p -> if (p?.type == PieceType.BISHOP) (r + c) % 2 else null }
                }.toSet()
                squareColors.size == 1
            }
        }
    }

    private fun enPassantCaptureRow(color: PieceColor, endRow: Int): Int =
        if (color == PieceColor.WHITE) endRow + 1 else endRow - 1

    private fun calculateAmbiguity(start: Position, end: Position, movingPiece: Piece): String {
        if (movingPiece.type == PieceType.PAWN || movingPiece.type == PieceType.KING) return ""

        val alternatives = pieces.flatMapIndexed { r, cols ->
            cols.mapIndexedNotNull { c, p ->
                if (p != null && !(r == start.row && c == start.col) &&
                    p.type == movingPiece.type && p.color == movingPiece.color &&
                    isValidMoveIgnoringCheck(Position(r, c), end) &&
                    !movePieceInternal(Position(r, c), end).isKingInCheck(movingPiece.color)
                ) Position(r, c) else null
            }
        }

        if (alternatives.isEmpty()) return ""
        val sameFile = alternatives.any { it.col == start.col }
        val sameRank = alternatives.any { it.row == start.row }
        return when {
            sameFile && sameRank -> "${getFileChar(start.col)}${getRankChar(start.row)}"
            sameFile -> "${getRankChar(start.row)}"
            else -> "${getFileChar(start.col)}"
        }
    }

    private fun findKing(kingColor: PieceColor): Position? {
        pieces.forEachIndexed { row, cols ->
            cols.forEachIndexed { col, piece ->
                if (piece?.type == PieceType.KING && piece.color == kingColor)
                    return Position(row, col)
            }
        }
        return null
    }

    private fun isValidPawnMove(start: Position, end: Position, color: PieceColor): Boolean {
        val direction = if (color == PieceColor.WHITE) -1 else 1
        val startRow = if (color == PieceColor.WHITE) 6 else 1

        if (start.col == end.col) {
            if (pieces[end.row][end.col] != null) return false
            if (start.row + direction == end.row) return true
            if (start.row == startRow && start.row + 2 * direction == end.row && pieces[start.row + direction][start.col] == null) return true
        }
        if (abs(start.col - end.col) == 1 && start.row + direction == end.row) {
            return pieces[end.row][end.col] != null || isEnPassant(start, end)
        }
        return false
    }

    private fun isEnPassant(start: Position, end: Position): Boolean {
        val last = lastMove ?: return false
        val lastPiece = pieces[last.end.row][last.end.col] ?: return false
        if (lastPiece.type != PieceType.PAWN || abs(last.start.row - last.end.row) != 2) return false
        val pawnRow = if (lastPiece.color == PieceColor.WHITE) 4 else 3
        return start.row == pawnRow && end.col == last.end.col &&
                end.row == last.end.row + if (lastPiece.color == PieceColor.WHITE) 1 else -1
    }

    private fun isPromotionSquare(piece: Piece, position: Position): Boolean =
        piece.type == PieceType.PAWN &&
                ((piece.color == PieceColor.WHITE && position.row == 0) ||
                        (piece.color == PieceColor.BLACK && position.row == 7))

    private fun isValidRookMove(start: Position, end: Position): Boolean =
        (start.row == end.row || start.col == end.col) && !isPathBlocked(start, end)

    private fun isValidKnightMove(start: Position, end: Position): Boolean {
        val rowDiff = abs(start.row - end.row)
        val colDiff = abs(start.col - end.col)
        return (rowDiff == 2 && colDiff == 1) || (rowDiff == 1 && colDiff == 2)
    }

    private fun isValidBishopMove(start: Position, end: Position): Boolean =
        abs(start.row - end.row) == abs(start.col - end.col) && !isPathBlocked(start, end)

    private fun isValidKingMove(start: Position, end: Position, piece: Piece): Boolean {
        val rowDiff = abs(start.row - end.row)
        val colDiff = abs(start.col - end.col)

        if (!piece.hasMoved && rowDiff == 0 && colDiff == 2) {
            if (isKingInCheck(piece.color)) return false
            val rookCol = if (end.col > start.col) 7 else 0
            val rook = pieces[start.row][rookCol]
            if (rook != null && !rook.hasMoved && rook.type == PieceType.ROOK) {
                val direction = if (end.col > start.col) 1 else -1
                var current = start.col + direction
                while (current != rookCol) {
                    if (pieces[start.row][current] != null) return false
                    if (abs(current - start.col) <= 2) {
                        if (movePieceInternal(start, Position(start.row, current)).isKingInCheck(piece.color)) return false
                    }
                    current += direction
                }
                return true
            }
        }
        return rowDiff <= 1 && colDiff <= 1
    }

    private fun isPathBlocked(start: Position, end: Position): Boolean {
        val rowStep = (end.row - start.row).coerceIn(-1, 1)
        val colStep = (end.col - start.col).coerceIn(-1, 1)
        var currentRow = start.row + rowStep
        var currentCol = start.col + colStep
        while (currentRow != end.row || currentCol != end.col) {
            if (pieces[currentRow][currentCol] != null) return true
            currentRow += rowStep
            currentCol += colStep
        }
        return false
    }

    fun toFen(): String {
        val boardStr = pieces.joinToString("/") { row ->
            buildString {
                var empty = 0
                for (piece in row) {
                    if (piece == null) {
                        empty++
                    } else {
                        if (empty > 0) { append(empty); empty = 0 }
                        append(piece.fenChar)
                    }
                }
                if (empty > 0) append(empty)
            }
        }

        val turn = if (moves.lastOrNull()?.piece?.color == PieceColor.WHITE) "b" else "w"

        var castling = ""
        pieces[7][4]?.takeIf { it.type == PieceType.KING && !it.hasMoved }?.let {
            if (pieces[7][7]?.let { r -> r.type == PieceType.ROOK && !r.hasMoved } == true) castling += "K"
            if (pieces[7][0]?.let { r -> r.type == PieceType.ROOK && !r.hasMoved } == true) castling += "Q"
        }
        pieces[0][4]?.takeIf { it.type == PieceType.KING && !it.hasMoved }?.let {
            if (pieces[0][7]?.let { r -> r.type == PieceType.ROOK && !r.hasMoved } == true) castling += "k"
            if (pieces[0][0]?.let { r -> r.type == PieceType.ROOK && !r.hasMoved } == true) castling += "q"
        }

        val enPassant = lastMove?.takeIf { it.piece.type == PieceType.PAWN && abs(it.start.row - it.end.row) == 2 }?.let {
            "${getFileChar(it.start.col)}${if (it.piece.color == PieceColor.WHITE) '3' else '6'}"
        } ?: "-"

        return "$boardStr $turn ${castling.ifEmpty { "-" }} $enPassant 0 1"
    }

    private val Piece.fenChar: String get() {
        val letter = when (type) {
            PieceType.KING -> "k"; PieceType.QUEEN -> "q"; PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"; PieceType.KNIGHT -> "n"; PieceType.PAWN -> "p"
        }
        return if (color == PieceColor.WHITE) letter.uppercase() else letter
    }

    companion object {
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
    }
}
