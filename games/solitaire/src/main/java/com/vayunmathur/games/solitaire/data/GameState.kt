package com.vayunmathur.games.solitaire.data

data class TableauPile(
    val faceDown: List<Card> = emptyList(),
    val faceUp: List<Card> = emptyList()
)

enum class GameMode { KLONDIKE, SPIDER, FREECELL, PYRAMID }

enum class DrawMode { DRAW_ONE, DRAW_THREE }

/**
 * Klondike redeal difficulty: how many passes through the stock are allowed.
 *
 * The names are part of the persisted stats key (`<draw>_<difficulty>`, see
 * SolitaireKlondikeOps), so never rename them; append new values, never reorder.
 */
enum class KlondikeDifficulty { RELAXED, REGULAR, HARD, TWO_PASSES }

/** Redeals (waste recycles) allowed for each difficulty; RELAXED is unlimited. */
fun KlondikeDifficulty.redeals(): Int = when (this) {
    KlondikeDifficulty.RELAXED -> Int.MAX_VALUE
    KlondikeDifficulty.REGULAR -> 2   // 3 passes total
    KlondikeDifficulty.TWO_PASSES -> 1
    KlondikeDifficulty.HARD -> 0      // single pass
}

/** Passes through the stock for each difficulty, or null when unlimited. */
fun KlondikeDifficulty.passes(): Int? = redeals().takeIf { it != Int.MAX_VALUE }?.plus(1)

/** The New Game dialog's order: most passes first. */
val KLONDIKE_DIFFICULTY_ORDER: List<KlondikeDifficulty> = listOf(
    KlondikeDifficulty.RELAXED,
    KlondikeDifficulty.REGULAR,
    KlondikeDifficulty.TWO_PASSES,
    KlondikeDifficulty.HARD,
)

/**
 * Options chosen in the New Game dialog. Only the fields relevant to the chosen
 * [GameMode] are used:
 *  - Klondike: [drawMode] + [klondikeDifficulty] (unlimited / 3 / 2 / 1 passes).
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
    val tableauPiles: List<TableauPile> = List(size = KLONDIKE_TABLEAU_COUNT) { TableauPile() },
    val foundations: List<List<Card>> = List(size = FOUNDATION_COUNT) { emptyList() },
    val drawMode: DrawMode = DrawMode.DRAW_ONE,
    val difficulty: KlondikeDifficulty = KlondikeDifficulty.REGULAR,
    val redealsRemaining: Int = 2,
    val variant: String = "",
    /** Deal seed (see domain/SolitaireDeals): re-dealing it reproduces this board. */
    val seed: Long = 0L,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
) {
    fun tick(): KlondikeState =
        if (isWon) this else copy(elapsedSeconds = elapsedSeconds + 1)
}

data class SpiderState(
    val tableauPiles: List<TableauPile> = List(size = SPIDER_TABLEAU_COUNT) { TableauPile() },
    val stockGroups: List<List<Card>> = emptyList(),
    val suitCount: Int = 4,
    val variant: String = "",
    val completedSuits: Int = 0,
    /** Deal seed (see domain/SolitaireDeals): re-dealing it reproduces this board. */
    val seed: Long = 0L,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
) {
    fun tick(): SpiderState =
        if (isWon) this else copy(elapsedSeconds = elapsedSeconds + 1)
}

data class FreeCellState(
    val tableauPiles: List<List<Card>> = List(size = FREECELL_TABLEAU_COUNT) { emptyList() },
    val freeCells: List<Card?> = List(size = FREECELL_COUNT) { null },
    val foundations: List<List<Card>> = List(size = FOUNDATION_COUNT) { emptyList() },
    val variant: String = "",
    /** Deal seed (see domain/SolitaireDeals): re-dealing it reproduces this board. */
    val seed: Long = 0L,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
) {
    fun tick(): FreeCellState =
        if (isWon) this else copy(elapsedSeconds = elapsedSeconds + 1)
}

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
    /** Deal seed (see domain/SolitaireDeals): re-dealing it reproduces this board. */
    val seed: Long = 0L,
    val moveCount: Int = 0,
    val elapsedSeconds: Int = 0,
    val usedUndo: Boolean = false,
    val isWon: Boolean = false
) {
    fun tick(): PyramidState =
        if (isWon) this else copy(elapsedSeconds = elapsedSeconds + 1)
}

data class SolitaireUiState(
    val gameMode: GameMode? = null,
    val klondike: KlondikeState? = null,
    val spider: SpiderState? = null,
    val freeCell: FreeCellState? = null,
    val pyramid: PyramidState? = null,
    val history: List<Any> = emptyList()
)

const val KLONDIKE_TABLEAU_COUNT = 7
const val SPIDER_TABLEAU_COUNT = 10
const val FREECELL_TABLEAU_COUNT = 8
const val FREECELL_COUNT = 4
const val FOUNDATION_COUNT = 4

/** Columns in a Spider deal that start with six cards instead of five. */
const val SPIDER_TALL_COLUMN_COUNT = 4

/** Cards in a tall Spider column (one face-up on top of five face-down). */
const val SPIDER_TALL_COLUMN_CARDS = 6

/** Cards in a standard Spider column (one face-up on top of four face-down). */
const val SPIDER_COLUMN_CARDS = 5

/** Cards in a complete same-suit run, which clears from the board. */
const val FULL_SUIT_SIZE = 13

/** Complete runs needed to win Spider (eight in a 104-card deck). */
const val TOTAL_SPIDER_SUITS = 8

/** Rows in the Pyramid layout (row r holds r+1 cards). */
const val PYRAMID_ROWS = 7

/** Two Pyramid cards clear together when their ranks sum to this. */
const val PAIR_TARGET = 13
