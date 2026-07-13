package com.vayunmathur.games.solitaire.data

data class TableauPile(
    val faceDown: List<Card> = emptyList(),
    val faceUp: List<Card> = emptyList()
)

enum class GameMode { KLONDIKE, SPIDER, FREECELL, PYRAMID }

enum class DrawMode { DRAW_ONE, DRAW_THREE }

/** Klondike redeal difficulty: how many times the waste may be recycled. */
enum class KlondikeDifficulty { RELAXED, REGULAR, HARD }

/** Redeals allowed for each difficulty (RELAXED is effectively unlimited). */
fun KlondikeDifficulty.redeals(): Int = when (this) {
    KlondikeDifficulty.RELAXED -> Int.MAX_VALUE
    KlondikeDifficulty.REGULAR -> 2   // 3 passes total
    KlondikeDifficulty.HARD -> 0      // single pass
}

/**
 * Options chosen in the New Game dialog. Only the fields relevant to the chosen
 * [GameMode] are used:
 *  - Klondike: [drawMode] + [klondikeDifficulty] (Relaxed/Regular/Hard redeals).
 *  - Spider: [spiderSuits] (1 = easy, 2 = medium, 4 = hard).
 *  - Pyramid: [relaxed] (unlimited passes vs a single pass).
 */
data class GameConfig(
    val drawMode: DrawMode = DrawMode.DRAW_ONE,
    val klondikeDifficulty: KlondikeDifficulty = KlondikeDifficulty.REGULAR,
    val relaxed: Boolean = false,
    val spiderSuits: Int = 4,
)

data class KlondikeState(
    val stock: List<Card> = emptyList(),
    val waste: List<Card> = emptyList(),
    val tableauPiles: List<TableauPile> = List(7) { TableauPile() },
    val foundations: List<List<Card>> = List(4) { emptyList() },
    val drawMode: DrawMode = DrawMode.DRAW_ONE,
    val difficulty: KlondikeDifficulty = KlondikeDifficulty.REGULAR,
    val redealsRemaining: Int = 2,
    val variant: String = "",
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

data class SpiderState(
    val tableauPiles: List<TableauPile> = List(10) { TableauPile() },
    val stockGroups: List<List<Card>> = emptyList(),
    val suitCount: Int = 4,
    val variant: String = "",
    val completedSuits: Int = 0,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

data class FreeCellState(
    val tableauPiles: List<List<Card>> = List(8) { emptyList() },
    val freeCells: List<Card?> = List(4) { null },
    val foundations: List<List<Card>> = List(4) { emptyList() },
    val variant: String = "",
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

/**
 * Pyramid solitaire. [pyramid] is 7 rows (row r has r+1 slots); a removed card
 * becomes null so positions stay stable for the triangular layout. Cards are
 * removed in pairs whose ranks sum to 13 (Ace=1 … King=13); a King (13) is
 * removed on its own. Additionally an exposed card may be removed together with
 * a card it covers, when it is that card's only remaining cover.
 * [selectedId] is the currently picked card ("pyr_r_c" or "waste"). When
 * [relaxed] the waste may be recycled into the stock without limit; otherwise
 * there is a single pass. Winning = the whole pyramid is cleared.
 */
data class PyramidState(
    val pyramid: List<List<Card?>> = emptyList(),
    val stock: List<Card> = emptyList(),
    val waste: List<Card> = emptyList(),
    val relaxed: Boolean = false,
    val variant: String = "",
    val selectedId: String? = null,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
)

data class SolitaireUiState(
    val gameMode: GameMode? = null,
    val klondike: KlondikeState? = null,
    val spider: SpiderState? = null,
    val freeCell: FreeCellState? = null,
    val pyramid: PyramidState? = null,
    val history: List<Any> = emptyList()
)
