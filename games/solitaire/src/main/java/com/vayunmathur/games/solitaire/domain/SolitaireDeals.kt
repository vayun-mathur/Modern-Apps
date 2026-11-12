package com.vayunmathur.games.solitaire.domain

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.FOUNDATION_COUNT
import com.vayunmathur.games.solitaire.data.FREECELL_COUNT
import com.vayunmathur.games.solitaire.data.FREECELL_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KLONDIKE_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PYRAMID_ROWS
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SPIDER_COLUMN_CARDS
import com.vayunmathur.games.solitaire.data.SPIDER_TABLEAU_COUNT
import com.vayunmathur.games.solitaire.data.SPIDER_TALL_COLUMN_CARDS
import com.vayunmathur.games.solitaire.data.SPIDER_TALL_COLUMN_COUNT
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import com.vayunmathur.games.solitaire.data.createSpiderDeck
import com.vayunmathur.games.solitaire.data.redeals

/**
 * Pure deal construction: the one place a seed becomes a starting position.
 *
 * Layouts are exactly what the `new*GameImpl` functions dealt before seeds were
 * kept (same [createShuffledDeck] / [createSpiderDeck] shuffle, same dealing
 * order), so a seed always deals the same board on every device and version,
 * and the offline solver sees precisely the board the player gets.
 */
object SolitaireDeals {

    fun klondike(seed: Long, config: GameConfig): KlondikeState {
        val deck = createShuffledDeck(seed)
        val tableauPiles = mutableListOf<TableauPile>()
        var index = 0
        for (i in 0 until KLONDIKE_TABLEAU_COUNT) {
            val faceDown = deck.subList(index, index + i)
            index += i
            val faceUp = listOf(deck[index])
            index++
            tableauPiles.add(TableauPile(faceDown, faceUp))
        }
        return KlondikeState(
            stock = deck.subList(index, deck.size),
            waste = emptyList(),
            tableauPiles = tableauPiles,
            foundations = List(FOUNDATION_COUNT) { emptyList() },
            drawMode = config.drawMode,
            difficulty = config.klondikeDifficulty,
            redealsRemaining = config.klondikeDifficulty.redeals(),
            variant = SolvableDeals.variant(GameMode.KLONDIKE, config),
            seed = seed,
        )
    }

    fun freeCell(seed: Long): FreeCellState {
        val deck = createShuffledDeck(seed)
        val piles = List(FREECELL_TABLEAU_COUNT) { mutableListOf<Card>() }
        for (i in deck.indices) piles[i % FREECELL_TABLEAU_COUNT].add(deck[i])
        return FreeCellState(
            tableauPiles = piles.map { it.toList() },
            freeCells = List(FREECELL_COUNT) { null },
            foundations = List(FOUNDATION_COUNT) { emptyList() },
            variant = SolvableDeals.variant(GameMode.FREECELL, GameConfig()),
            seed = seed,
        )
    }

    fun spider(seed: Long, config: GameConfig): SpiderState {
        val deck = createSpiderDeck(config.spiderSuits, seed)
        val tableauPiles = mutableListOf<TableauPile>()
        var index = 0
        for (i in 0 until SPIDER_TABLEAU_COUNT) {
            val count = if (i < SPIDER_TALL_COLUMN_COUNT) SPIDER_TALL_COLUMN_CARDS else SPIDER_COLUMN_CARDS
            val faceDown = deck.subList(index, index + count - 1)
            index += count - 1
            val faceUp = listOf(deck[index])
            index++
            tableauPiles.add(TableauPile(faceDown, faceUp))
        }
        return SpiderState(
            tableauPiles = tableauPiles,
            stockGroups = deck.subList(index, deck.size).chunked(SPIDER_TABLEAU_COUNT),
            suitCount = config.spiderSuits,
            variant = SolvableDeals.variant(GameMode.SPIDER, config),
            seed = seed,
        )
    }

    fun pyramid(seed: Long, config: GameConfig): PyramidState {
        val deck = createShuffledDeck(seed)
        var index = 0
        val rows = mutableListOf<List<Card?>>()
        for (r in 0 until PYRAMID_ROWS) {
            val row = mutableListOf<Card?>()
            for (c in 0..r) {
                row.add(deck[index])
                index++
            }
            rows.add(row)
        }
        return PyramidState(
            pyramid = rows,
            stock = deck.subList(index, deck.size).toList(),
            waste = emptyList(),
            relaxed = config.relaxed,
            variant = SolvableDeals.variant(GameMode.PYRAMID, config),
            seed = seed,
        )
    }
}

/** Solver card code: `suit.ordinal * 13 + rank - 1`, so 0..51; suits 0-1 are red. */
internal fun Card.code(): Int = suit.ordinal * RANKS + rank.value - 1

internal fun codeToCard(code: Int): Card = Card(Suit.entries[code / RANKS], Rank.entries[code % RANKS])

internal fun codeSuit(code: Int): Int = code / RANKS

/** Rank 1 (Ace) .. 13 (King). */
internal fun codeRank(code: Int): Int = code % RANKS + 1

internal fun codeIsRed(code: Int): Boolean = code / RANKS < 2

internal fun DrawMode.cardsPerDraw(): Int = if (this == DrawMode.DRAW_THREE) 3 else 1

internal const val RANKS = 13
