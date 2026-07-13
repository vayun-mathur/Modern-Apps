package com.vayunmathur.games.solitaire.data

enum class Suit(val symbol: String, val isRed: Boolean) {
    HEARTS("♥", true),
    DIAMONDS("♦", true),
    SPADES("♠", false),
    CLUBS("♣", false)
}

enum class Rank(val display: String, val value: Int) {
    ACE("A", 1),
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    FIVE("5", 5),
    SIX("6", 6),
    SEVEN("7", 7),
    EIGHT("8", 8),
    NINE("9", 9),
    TEN("10", 10),
    JACK("J", 11),
    QUEEN("Q", 12),
    KING("K", 13)
}

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
