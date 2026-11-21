package com.vayunmathur.games.chess.data

import kotlin.math.abs

/**
 * FEN serialization plus puzzle-board construction for [Board].
 *
 * Kept out of [Board] itself so the data class stays under detekt's
 * TooManyFunctions cap. Public signatures are unchanged (`Board.fromFen`,
 * `Board.fromPuzzle`, `board.toFen()`), so callers and tests are untouched.
 */

fun Board.toFen(): String {
    val boardStr = pieces.joinToString("/") { row -> encodeFenRow(row) }
    val turn = if (moves.lastOrNull()?.piece?.color == PieceColor.WHITE) "b" else "w"
    val castling = fenCastling()
    val enPassant = fenEnPassant()
    return "$boardStr $turn ${castling.ifEmpty { "-" }} $enPassant 0 1"
}

/** UCI text for this move (`e2e4`, `e7e8q`): the move-list encoding saved games persist. */
fun Move.toUci(): String = buildString {
    append(getFileChar(start.col)); append(getRankChar(start.row))
    append(getFileChar(end.col)); append(getRankChar(end.row))
    promotedTo?.let {
        append(
            when (it) {
                PieceType.QUEEN -> 'q'; PieceType.ROOK -> 'r'
                PieceType.BISHOP -> 'b'; PieceType.KNIGHT -> 'n'
                else -> 'q'
            },
        )
    }
}

/** UCI text for a puzzle solution move: the encoding saved puzzles persist. */
fun PuzzleMove.toUci(): String = buildString {
    append(getFileChar(from.col)); append(getRankChar(from.row))
    append(getFileChar(to.col)); append(getRankChar(to.row))
    promotion?.let {
        append(
            when (it) {
                PieceType.QUEEN -> 'q'; PieceType.ROOK -> 'r'
                PieceType.BISHOP -> 'b'; PieceType.KNIGHT -> 'n'
                else -> 'q'
            },
        )
    }
}

/** Parses UCI text (`e2e4`, `e7e8q`) into from/to/promotion, or null when malformed. */
fun parseUciMove(uci: String): Triple<Position, Position, PieceType?>? {
    if (uci.length !in 4..5) return null
    fun square(file: Char, rank: Char): Position? {
        if (file !in 'a'..'h' || rank !in '1'..'8') return null
        return Position(8 - (rank - '0'), file - 'a')
    }
    val from = square(uci[0], uci[1]) ?: return null
    val to = square(uci[2], uci[3]) ?: return null
    val promotion = when (uci.getOrNull(4)) {
        null -> null
        'q' -> PieceType.QUEEN; 'r' -> PieceType.ROOK
        'b' -> PieceType.BISHOP; 'n' -> PieceType.KNIGHT
        else -> return null
    }
    return Triple(from, to, promotion)
}

private fun Board.encodeFenRow(row: List<Piece?>): String {
    return buildString {
        var empty = 0
        for (piece in row) {
            if (piece == null) {
                empty++
            } else {
                if (empty > 0) {
                    append(empty)
                    empty = 0
                }
                append(piece.fenChar)
            }
        }
        if (empty > 0) append(empty)
    }
}

private fun Board.fenCastling(): String {
    return buildString {
        appendCastlingSide(this@fenCastling, WHITE_HOME_ROW, 'K', 'Q')
        appendCastlingSide(this@fenCastling, BLACK_HOME_ROW, 'k', 'q')
    }
}

private fun StringBuilder.appendCastlingSide(
    board: Board,
    homeRow: Int,
    kingSide: Char,
    queenSide: Char,
) {
    board.pieces[homeRow][KING_HOME_FILE]?.takeIf {
        it.type == PieceType.KING && !it.hasMoved
    }?.let {
        if (board.isUnmovedRook(homeRow, ROOK_KINGSIDE_FILE)) append(kingSide)
        if (board.isUnmovedRook(homeRow, ROOK_QUEENSIDE_FILE)) append(queenSide)
    }
}

private fun Board.isUnmovedRook(row: Int, col: Int): Boolean {
    val rook = pieces[row][col]
    return rook?.type == PieceType.ROOK && !rook.hasMoved
}

private fun Board.fenEnPassant(): String {
    val last = lastMove
    if (last?.piece?.type != PieceType.PAWN) return "-"
    if (abs(last.start.row - last.end.row) != PAWN_DOUBLE_STEP) return "-"
    val rank = if (last.piece.color == PieceColor.WHITE) WHITE_EP_RANK else BLACK_EP_RANK
    return "${getFileChar(last.start.col)}$rank"
}

internal val Piece.fenChar: String get() {
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

internal fun getFileChar(col: Int): Char = 'a' + col
internal fun getRankChar(row: Int): Char = '8' - row

private const val FEN_RANK_COUNT = 8
private const val FEN_EP_NONE = "-"
private const val WHITE_EP_RANK = '3'
private const val BLACK_EP_RANK = '6'

internal fun parseFen(
    fen: String,
    pieceFromChar: (Char) -> Piece,
    buildBoard: (
        rawPieces: List<List<Piece?>>,
        sideToMove: PieceColor,
        castling: CastlingRights,
        epSquare: Position?,
    ) -> Board,
): Board {
    val fields = fen.trim().split(Regex("\\s+"))
    val grid = parsePlacement(fields[0], pieceFromChar)
    val sideToMove = if (fields.getOrElse(1) { "w" } == "b") PieceColor.BLACK else PieceColor.WHITE
    val castlingField = fields.getOrElse(2) { FEN_EP_NONE }
    val castling = CastlingRights(
        whiteKing = castlingField.contains('K'),
        whiteQueen = castlingField.contains('Q'),
        blackKing = castlingField.contains('k'),
        blackQueen = castlingField.contains('q')
    )
    val epSquare = parseEpSquare(fields.getOrElse(3) { FEN_EP_NONE })
    return buildBoard(grid.map { it.toList() }, sideToMove, castling, epSquare)
}

private fun parsePlacement(
    placement: String,
    pieceFromChar: (Char) -> Piece,
): MutableList<MutableList<Piece?>> {
    val grid = MutableList(FEN_RANK_COUNT) { MutableList<Piece?>(FEN_RANK_COUNT) { null } }
    var row = 0
    var col = 0
    for (ch in placement) {
        when {
            ch == '/' -> {
                row++
                col = 0
            }
            ch.isDigit() -> col += ch - '0'
            else -> {
                grid[row][col] = pieceFromChar(ch)
                col++
            }
        }
    }
    return grid
}

private fun parseEpSquare(epField: String): Position? {
    if (epField == FEN_EP_NONE) return null
    val file = epField[0] - 'a'
    val rank = epField[1] - '0'
    return Position(FEN_RANK_COUNT - rank, file)
}

internal fun buildPuzzleBoard(
    rawPieces: List<List<Piece?>>,
    sideToMove: PieceColor,
    castling: CastlingRights,
    epSquare: Position?,
): Board {
    val pieces = rawPieces.mapIndexed { r, row ->
        row.mapIndexed { c, p -> p?.copy(hasMoved = movedFor(p, r, c, castling)) }
    }
    val seed = seedLastMove(pieces, sideToMove, epSquare)
    return Board(
        pieces = pieces,
        lastMove = seed,
        moves = seed?.let { listOf(it) } ?: emptyList()
    )
}

private fun movedFor(p: Piece, r: Int, c: Int, castling: CastlingRights): Boolean {
    return when (p.type) {
        PieceType.KING -> kingMoved(p.color, castling)
        PieceType.ROOK -> rookMoved(p.color, r, c, castling)
        else -> true
    }
}

private fun kingMoved(color: PieceColor, castling: CastlingRights): Boolean {
    return if (color == PieceColor.WHITE) {
        !(castling.whiteKing || castling.whiteQueen)
    } else {
        !(castling.blackKing || castling.blackQueen)
    }
}

private fun rookMoved(color: PieceColor, r: Int, c: Int, castling: CastlingRights): Boolean {
    if (color == PieceColor.WHITE && r == WHITE_HOME_ROW && c == ROOK_KINGSIDE_FILE) {
        return !castling.whiteKing
    }
    if (color == PieceColor.WHITE && r == WHITE_HOME_ROW && c == ROOK_QUEENSIDE_FILE) {
        return !castling.whiteQueen
    }
    if (color == PieceColor.BLACK && r == BLACK_HOME_ROW && c == ROOK_KINGSIDE_FILE) {
        return !castling.blackKing
    }
    if (color == PieceColor.BLACK && r == BLACK_HOME_ROW && c == ROOK_QUEENSIDE_FILE) {
        return !castling.blackQueen
    }
    return true
}

/**
 * A synthetic previous move used to encode turn and en-passant into a puzzle
 * board. When [epSquare] is set it is the pawn double-step that created the
 * en-passant target; otherwise it is a zero-length move by the side that did
 * NOT just move, which only serves to make [toFen]'s turn inference correct.
 */
private fun seedLastMove(
    pieces: List<List<Piece?>>,
    sideToMove: PieceColor,
    epSquare: Position?,
): Move? {
    if (epSquare != null) return seedEpMove(pieces, sideToMove, epSquare)
    // No en-passant: seed a no-op move by the opponent so the turn reads back
    // correctly. A king "move" onto its own square is never a pawn double-step,
    // so it cannot be mistaken for an en-passant setup.
    val opponent = sideToMove.opposite
    pieces.forEachIndexed { r, row ->
        row.forEachIndexed { c, p ->
            if (p?.type == PieceType.KING && p.color == opponent) {
                val pos = Position(r, c)
                return Move(start = pos, end = pos, piece = p)
            }
        }
    }
    return null
}

private fun seedEpMove(
    pieces: List<List<Piece?>>,
    sideToMove: PieceColor,
    epSquare: Position,
): Move {
    // The pawn that just double-moved sits one square beyond the ep target,
    // in the direction the mover was heading.
    val (startPos, endPos) = if (sideToMove == PieceColor.WHITE) {
        // Black just moved e7->e5; ep target e6 (row epSquare.row).
        Position(epSquare.row - 1, epSquare.col) to Position(epSquare.row + 1, epSquare.col)
    } else {
        // White just moved e2->e4; ep target e3.
        Position(epSquare.row + 1, epSquare.col) to Position(epSquare.row - 1, epSquare.col)
    }
    val pawn = pieces[endPos.row][endPos.col]
        ?: Piece(PieceType.PAWN, sideToMove.opposite)
    return Move(start = startPos, end = endPos, piece = pawn)
}
