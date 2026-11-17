package com.vayunmathur.games.solitaire.data

/** Card suit with its display symbol and colour. */
enum class Suit(val symbol: String, val isRed: Boolean) {
    HEARTS("♥", true),
    DIAMONDS("♦", true),
    SPADES("♠", false),
    CLUBS("♣", false)
}

/** Card rank with its display label and numeric value. */
enum class Rank(val display: String, val value: Int) {
    ACE(display = "A", value = 1),
    TWO(display = "2", value = 2),
    THREE(display = "3", value = 3),
    FOUR(display = "4", value = 4),
    FIVE(display = "5", value = 5),
    SIX(display = "6", value = 6),
    SEVEN(display = "7", value = 7),
    EIGHT(display = "8", value = 8),
    NINE(display = "9", value = 9),
    TEN(display = "10", value = 10),
    JACK(display = "J", value = 11),
    QUEEN(display = "Q", value = 12),
    KING(display = "K", value = 13)
}

/** A single playing card identified by its suit and rank. */
data class Card(val suit: Suit, val rank: Rank)

fun createDeck(): List<Card> =
    Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(suit, rank) } }

fun createShuffledDeck(seed: Long = System.currentTimeMillis()): List<Card> =
    createDeck().shuffled(java.util.Random(seed))

/**
 * Builds a 104-card Spider deck for the given [suitCount] (1, 2, or 4), then
 * shuffles it. Fewer suits = easier: 1 suit uses 8 copies of each rank in one
 * suit, 2 suits use 4 copies across two suits, 4 suits use 2 full decks.
 */
fun createSpiderDeck(suitCount: Int, seed: Long = System.currentTimeMillis()): List<Card> {
    val suits = when (suitCount) {
        1 -> listOf(Suit.SPADES)
        2 -> listOf(Suit.SPADES, Suit.HEARTS)
        else -> Suit.entries.toList()
    }
    val copiesPerSuit = 104 / (suits.size * Rank.entries.size)
    val cards = mutableListOf<Card>()
    repeat(copiesPerSuit) {
        for (suit in suits) for (rank in Rank.entries) cards.add(Card(suit, rank))
    }
    return cards.shuffled(java.util.Random(seed))
}

fun Card.isOneHigherThan(other: Card): Boolean =
    rank.value == other.rank.value + 1

fun Card.alternatesColorWith(other: Card): Boolean =
    suit.isRed != other.suit.isRed
