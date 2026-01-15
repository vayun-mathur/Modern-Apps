package com.vayunmathur.games.chess

import kotlin.math.abs

enum class PieceType(
    val resID: Int
) {
    KING(R.drawable.chess_king_2_fill1_24px),
    QUEEN(R.drawable.chess_queen_fill1_24px),
    ROOK(R.drawable.chess_rook_fill1_24px),
    BISHOP(R.drawable.chess_bishop_fill1_24px),
    KNIGHT(R.drawable.chess_knight_fill1_24px),
    PAWN(R.drawable.chess_pawn_fill1_24px)
}
enum class PieceColor { WHITE, BLACK }

data class Piece(val type: PieceType, val color: PieceColor, val hasMoved: Boolean = false)

data class Position(val row: Int, val col: Int)

// ---------------------------------------------------------------------------
// Updated Move Class with Notation Logic
// ---------------------------------------------------------------------------
data class Move(
    val start: Position,
    val end: Position,
    val piece: Piece,
    val capturedPiece: Piece? = null,
    var promotedTo: PieceType? = null,
    val isCheck: Boolean = false,
    val isCheckmate: Boolean = false,
    val isCastling: Boolean = false,
    val ambiguity: String = "" // Disambiguation string, e.g., "b" for "Nbd7"
) {
    override fun toString(): String {
        if (isCastling) {
            return if (end.col > start.col) "O-O" else "O-O-O"
        }

        val sb = StringBuilder()

        // 1. Piece Letter (Skip for Pawns)
        if (piece.type != PieceType.PAWN) {
            sb.append(getPieceLetter(piece.type))
            sb.append(ambiguity) // Add disambiguation
        }

        // 2. Capture Notation
        if (capturedPiece != null) {
            if (piece.type == PieceType.PAWN) {
                sb.append(getFileChar(start.col)) // Pawns capture like "exd5"
            }
            sb.append("x")
        }

        // 3. Destination Square
        sb.append(getFileChar(end.col))
        sb.append(getRankChar(end.row))

        // 4. Promotion
        if (promotedTo != null) {
            sb.append("=")
            sb.append(getPieceLetter(promotedTo!!))
        }

        // 5. Check / Checkmate
        if (isCheckmate) {
            sb.append("#")
        } else if (isCheck) {
            sb.append("+")
        }

        return sb.toString()
    }

    private fun getPieceLetter(type: PieceType): String = when (type) {
        PieceType.KING -> "K"
        PieceType.QUEEN -> "Q"
        PieceType.ROOK -> "R"
        PieceType.BISHOP -> "B"
        PieceType.KNIGHT -> "N"
        PieceType.PAWN -> ""
    }

    private fun getFileChar(col: Int): Char = 'a' + col
    private fun getRankChar(row: Int): Char = '8' - row
}

// ---------------------------------------------------------------------------
// Complete Board Class
// ---------------------------------------------------------------------------
data class Board(
    val pieces: List<List<Piece?>>,
    val capturedByWhite: List<Piece> = emptyList(),
    val capturedByBlack: List<Piece> = emptyList(),
    val lastMove: Move? = null,
    val promotionPosition: Position? = null,
    val moves: List<Move> = emptyList()
) {

    // --- Core Move Logic (Public) ---

    fun movePiece(start: Position, end: Position, promoteTo: PieceType? = null): Board {
        val movingPiece = pieces[start.row][start.col]
            ?: throw IllegalStateException("No piece at start position")

        // 1. Calculate Ambiguity (MUST be done on the CURRENT board state)
        val ambiguity = calculateAmbiguity(start, end, movingPiece)

        // 2. Determine Capture (Standard or En Passant)
        var capturedPiece = pieces[end.row][end.col]
        val isEnPassantMove = movingPiece.type == PieceType.PAWN && isEnPassant(start, end)

        if (isEnPassantMove) {
            val captureRow = if (movingPiece.color == PieceColor.WHITE) end.row + 1 else end.row - 1
            capturedPiece = pieces[captureRow][end.col]
        }

        // 3. Execute Physical Move
        val newPieces = pieces.map { it.toMutableList() }.toMutableList()

        // Handle Castling (Move the Rook)
        var isCastlingMove = false
        if (movingPiece.type == PieceType.KING && abs(start.col - end.col) == 2) {
            isCastlingMove = true
            val rookStartCol = if (end.col > start.col) 7 else 0
            val rookEndCol = if (end.col > start.col) end.col - 1 else end.col + 1
            val rook = newPieces[start.row][rookStartCol]

            newPieces[start.row][rookEndCol] = rook?.copy(hasMoved = true)
            newPieces[start.row][rookStartCol] = null
        }

        // Handle En Passant Capture Removal
        if (isEnPassantMove) {
            val captureRow = if (movingPiece.color == PieceColor.WHITE) end.row + 1 else end.row - 1
            newPieces[captureRow][end.col] = null
        }

        // Place Moving Piece
        val finalPieceType = promoteTo ?: movingPiece.type
        newPieces[end.row][end.col] = movingPiece.copy(type = finalPieceType, hasMoved = true)
        newPieces[start.row][start.col] = null

        // 4. Update Captured Lists
        val newCapturedWhite = if (capturedPiece?.color == PieceColor.BLACK) capturedByWhite + capturedPiece else capturedByWhite
        val newCapturedBlack = if (capturedPiece?.color == PieceColor.WHITE) capturedByBlack + capturedPiece else capturedByBlack

        // 5. Determine Game State (Check/Mate) using a temporary board
        val tempNextBoard = Board(newPieces.map { it.toList() })
        val opponentColor = if (movingPiece.color == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
        val isCheck = tempNextBoard.isKingInCheck(opponentColor)
        val isCheckmate = tempNextBoard.isCheckmate(opponentColor)

        // 6. Create Full Move Object
        val fullMove = Move(
            start = start,
            end = end,
            piece = movingPiece,
            capturedPiece = capturedPiece,
            promotedTo = promoteTo,
            isCheck = isCheck,
            isCheckmate = isCheckmate,
            isCastling = isCastlingMove,
            ambiguity = ambiguity
        )

        val isPromotingNow = (promoteTo == null && isPromotionSquare(movingPiece, end))

        return Board(
            pieces = newPieces.map { it.toList() },
            capturedByWhite = newCapturedWhite,
            capturedByBlack = newCapturedBlack,
            lastMove = fullMove,
            promotionPosition = if (isPromotingNow) end else null,
            moves = moves + fullMove
        )
    }

    /**
     * Lightweight internal move function to simulate board states.
     * Does NOT calculate notation or ambiguity to prevent infinite recursion.
     */
    private fun movePieceInternal(start: Position, end: Position): Board {
        val newPieces = pieces.map { it.toMutableList() }.toMutableList()
        val piece = newPieces[start.row][start.col] ?: return this

        // Simple Capture Logic for validity checking
        if (piece.type == PieceType.PAWN && isEnPassant(start, end)) {
            val capRow = if (piece.color == PieceColor.WHITE) end.row + 1 else end.row - 1
            newPieces[capRow][end.col] = null
        }

        if (piece.type == PieceType.KING && abs(start.col - end.col) == 2) {
            val rookStartCol = if (end.col > start.col) 7 else 0
            val rookEndCol = if (end.col > start.col) end.col - 1 else end.col + 1
            newPieces[start.row][rookEndCol] = newPieces[start.row][rookStartCol]?.copy(hasMoved = true)
            newPieces[start.row][rookStartCol] = null
        }

        newPieces[end.row][end.col] = piece.copy(hasMoved = true)
        newPieces[start.row][start.col] = null

        return this.copy(pieces = newPieces.map { it.toList() }, lastMove = Move(start, end, piece))
    }

    fun promotePawn(position: Position, to: PieceType): Board {
        val newPieces = pieces.map { it.toMutableList() }.toMutableList()
        val piece = newPieces[position.row][position.col]!!
        val newPiece = piece.copy(type = to)
        newPieces[position.row][position.col] = newPiece

        // Create a temporary board with the promoted piece to check game state
        val tempBoard = this.copy(pieces = newPieces.map { it.toList() })
        val opponentColor = if (piece.color == PieceColor.WHITE) PieceColor.BLACK else PieceColor.WHITE
        val isCheck = tempBoard.isKingInCheck(opponentColor)
        val isCheckmate = tempBoard.isCheckmate(opponentColor)

        // Update the last move in the history to reflect the promotion and game state
        val updatedMoves = moves.toMutableList()
        var updatedLastMove: Move? = null
        if (updatedMoves.isNotEmpty()) {
            val last = updatedMoves.removeAt(updatedMoves.lastIndex)
            updatedLastMove = last.copy(promotedTo = to, isCheck = isCheck, isCheckmate = isCheckmate)
            updatedMoves.add(updatedLastMove)
        }

        return this.copy(
            pieces = newPieces.map { it.toList() },
            promotionPosition = null,
            moves = updatedMoves,
            lastMove = updatedLastMove ?: lastMove
        )
    }

    // --- Validation Logic ---

    fun isValidMove(start: Position, end: Position): Boolean {
        val piece = pieces[start.row][start.col] ?: return false

        // 1. Check movement rules
        if (!isValidMoveIgnoringCheck(start, end)) return false

        // 2. Check if King is safe after move (using internal simulation)
        val newBoard = movePieceInternal(start, end)
        return !newBoard.isKingInCheck(piece.color)
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
            PieceType.QUEEN -> isValidQueenMove(start, end)
            PieceType.KING -> isValidKingMove(start, end, piece)
        }
    }

    fun isKingInCheck(kingColor: PieceColor): Boolean {
        val kingPosition = findKing(kingColor) ?: return false
        for (row in pieces.indices) {
            for (col in pieces[row].indices) {
                val piece = pieces[row][col]
                if (piece != null && piece.color != kingColor) {
                    if (isValidMoveIgnoringCheck(Position(row, col), kingPosition)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun isCheckmate(kingColor: PieceColor): Boolean {
        if (!isKingInCheck(kingColor)) return false

        for (row in pieces.indices) {
            for (col in pieces[row].indices) {
                val piece = pieces[row][col]
                if (piece != null && piece.color == kingColor) {
                    for (i in 0..7) {
                        for (j in 0..7) {
                            val newPosition = Position(i, j)
                            if (isValidMove(Position(row, col), newPosition)) {
                                return false // Found a legal move
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    // --- Helper Functions ---

    private fun calculateAmbiguity(start: Position, end: Position, movingPiece: Piece): String {
        if (movingPiece.type == PieceType.PAWN || movingPiece.type == PieceType.KING) return ""

        val alternatives = mutableListOf<Position>()

        for (r in pieces.indices) {
            for (c in pieces[r].indices) {
                val p = pieces[r][c]
                // Find OTHER pieces of same type/color
                if (p != null && p !== movingPiece && p.type == movingPiece.type && p.color == movingPiece.color) {
                    val pos = Position(r, c)
                    // Can it reach the destination?
                    if (isValidMoveIgnoringCheck(pos, end)) {
                        // Is the move legal (doesn't expose king)?
                        val simBoard = movePieceInternal(pos, end)
                        if (!simBoard.isKingInCheck(movingPiece.color)) {
                            alternatives.add(pos)
                        }
                    }
                }
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
        for (row in pieces.indices) {
            for (col in pieces[row].indices) {
                val piece = pieces[row][col]
                if (piece != null && piece.type == PieceType.KING && piece.color == kingColor) {
                    return Position(row, col)
                }
            }
        }
        return null
    }

    private fun isValidPawnMove(start: Position, end: Position, color: PieceColor): Boolean {
        val direction = if (color == PieceColor.WHITE) -1 else 1
        val startRow = if (color == PieceColor.WHITE) 6 else 1

        // Move forward
        if (start.col == end.col) {
            if (pieces[end.row][end.col] != null) return false
            if (start.row + direction == end.row) return true
            if (start.row == startRow && start.row + 2 * direction == end.row && pieces[start.row + direction][start.col] == null) return true
        }
        // Capture
        if (abs(start.col - end.col) == 1 && start.row + direction == end.row) {
            return pieces[end.row][end.col] != null || isEnPassant(start, end)
        }

        return false
    }

    fun isEnPassant(start: Position, end: Position): Boolean {
        val last = lastMove ?: return false
        val lastPiece = pieces[last.end.row][last.end.col] ?: return false

        if (lastPiece.type == PieceType.PAWN && abs(last.start.row - last.end.row) == 2) {
            val pawnRow = if (lastPiece.color == PieceColor.WHITE) 4 else 3
            if (start.row == pawnRow && end.col == last.end.col && end.row == last.end.row + if (lastPiece.color == PieceColor.WHITE) 1 else -1) {
                return true
            }
        }
        return false
    }

    private fun isPromotionSquare(piece: Piece, position: Position): Boolean {
        return piece.type == PieceType.PAWN &&
                ((piece.color == PieceColor.WHITE && position.row == 0) ||
                        (piece.color == PieceColor.BLACK && position.row == 7))
    }

    private fun isValidRookMove(start: Position, end: Position): Boolean {
        if (start.row != end.row && start.col != end.col) return false
        return !isPathBlocked(start, end)
    }

    private fun isValidKnightMove(start: Position, end: Position): Boolean {
        val rowDiff = abs(start.row - end.row)
        val colDiff = abs(start.col - end.col)
        return (rowDiff == 2 && colDiff == 1) || (rowDiff == 1 && colDiff == 2)
    }

    private fun isValidBishopMove(start: Position, end: Position): Boolean {
        if (abs(start.row - end.row) != abs(start.col - end.col)) return false
        return !isPathBlocked(start, end)
    }

    private fun isValidQueenMove(start: Position, end: Position): Boolean {
        return isValidRookMove(start, end) || isValidBishopMove(start, end)
    }

    private fun isValidKingMove(start: Position, end: Position, piece: Piece): Boolean {
        val rowDiff = abs(start.row - end.row)
        val colDiff = abs(start.col - end.col)

        // Castling
        if (!piece.hasMoved && rowDiff == 0 && colDiff == 2) {
            // Cannot castle out of check
            if (isKingInCheck(piece.color)) return false

            val rookCol = if (end.col > start.col) 7 else 0
            val rook = pieces[start.row][rookCol]

            if (rook != null && !rook.hasMoved && rook.type == PieceType.ROOK) {
                val direction = if (end.col > start.col) 1 else -1
                var current = start.col + direction

                // Check path for pieces and attacks
                while (current != rookCol) {
                    // 1. Path must be empty
                    if (pieces[start.row][current] != null) return false

                    // 2. King cannot pass through check (only check squares king actually steps on)
                    // The king moves 2 squares. We check the square he crosses and the destination.
                    if (abs(current - start.col) <= 2) {
                        val simBoard = movePieceInternal(start, Position(start.row, current))
                        if (simBoard.isKingInCheck(piece.color)) return false
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
        val sb = StringBuilder()
        for (row in pieces) {
            var empty = 0
            for (piece in row) {
                if (piece == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(getPieceLetter(piece.type, piece.color))
                }
            }
            if (empty > 0) {
                sb.append(empty)
            }
            sb.append("/")
        }
        sb.deleteCharAt(sb.length - 1)

        // Turn
        val turn = moves.lastOrNull()?.piece?.color?.let { if (it == PieceColor.WHITE) "b" else "w" } ?: "w"
        sb.append(" $turn")

        // Castling availability
        var castling = ""
        val whiteKing = pieces[7][4]
        val blackKing = pieces[0][4]
        if (whiteKing?.type == PieceType.KING && !whiteKing.hasMoved) {
            val wrk = pieces[7][7]
            if (wrk?.type == PieceType.ROOK && !wrk.hasMoved) castling += "K"
            val wqr = pieces[7][0]
            if (wqr?.type == PieceType.ROOK && !wqr.hasMoved) castling += "Q"
        }
        if (blackKing?.type == PieceType.KING && !blackKing.hasMoved) {
            val brk = pieces[0][7]
            if (brk?.type == PieceType.ROOK && !brk.hasMoved) castling += "k"
            val bqr = pieces[0][0]
            if (bqr?.type == PieceType.ROOK && !bqr.hasMoved) castling += "q"
        }
        sb.append(" ${if (castling.isEmpty()) "-" else castling}")

        // En passant target square
        val enPassant = if (lastMove != null && lastMove.piece.type == PieceType.PAWN && abs(lastMove.start.row - lastMove.end.row) == 2) {
            val file = getFileChar(lastMove.start.col)
            val rank = if (lastMove.piece.color == PieceColor.WHITE) '3' else '6'
            "$file$rank"
        } else {
            "-"
        }
        sb.append(" $enPassant")

        // Halfmove clock and fullmove number (not implemented, using 0 and 1)
        sb.append(" 0 1")

        return sb.toString()
    }

    private fun getPieceLetter(type: PieceType, color: PieceColor): String {
        val letter = when (type) {
            PieceType.KING -> "k"
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            PieceType.PAWN -> "p"
        }
        return if (color == PieceColor.WHITE) letter.uppercase() else letter
    }
    private fun getFileChar(col: Int): Char = 'a' + col
    private fun getRankChar(row: Int): Char = '8' - row

    companion object {
        val initialState = Board(
            pieces = listOf(
                listOf(
                    Piece(PieceType.ROOK, PieceColor.BLACK),
                    Piece(PieceType.KNIGHT, PieceColor.BLACK),
                    Piece(PieceType.BISHOP, PieceColor.BLACK),
                    Piece(PieceType.QUEEN, PieceColor.BLACK),
                    Piece(PieceType.KING, PieceColor.BLACK),
                    Piece(PieceType.BISHOP, PieceColor.BLACK),
                    Piece(PieceType.KNIGHT, PieceColor.BLACK),
                    Piece(PieceType.ROOK, PieceColor.BLACK)
                ),
                List(8) { Piece(PieceType.PAWN, PieceColor.BLACK) },
                List(8) { null },
                List(8) { null },
                List(8) { null },
                List(8) { null },
                List(8) { Piece(PieceType.PAWN, PieceColor.WHITE) },
                listOf(
                    Piece(PieceType.ROOK, PieceColor.WHITE),
                    Piece(PieceType.KNIGHT, PieceColor.WHITE),
                    Piece(PieceType.BISHOP, PieceColor.WHITE),
                    Piece(PieceType.QUEEN, PieceColor.WHITE),
                    Piece(PieceType.KING, PieceColor.WHITE),
                    Piece(PieceType.BISHOP, PieceColor.WHITE),
                    Piece(PieceType.KNIGHT, PieceColor.WHITE),
                    Piece(PieceType.ROOK, PieceColor.WHITE)
                ),
            )
        )
    }
}
