package com.vayunmathur.maps.util

/**
 * Ordinal -> dense slot table for the name search, with one word-position mask per query
 * token: bit `w` of token `t`'s mask is set when word `w` of the record's name starts with
 * token `t`. Open addressing over primitive arrays, so a table of a few hundred thousand
 * ordinals (a common word's whole range) costs a few MB and no boxing.
 */
internal class HitTable(private val tokenCount: Int, expected: Int) {
    private var slots = IntArray(capacityFor(expected)) { EMPTY }
    private var slotMask = slots.size - 1

    /** Ordinal of each dense index, in insertion order. */
    var ordinals = IntArray(maxOf(expected, MIN_DENSE))
        private set
    private var masks = LongArray(ordinals.size * tokenCount)

    /** Number of distinct ordinals added. */
    var size = 0
        private set

    /** Dense index of [ordinal], or -1. */
    fun indexOf(ordinal: Int): Int {
        var i = mix(ordinal) and slotMask
        var s = slots[i]
        while (s != EMPTY && ordinals[s] != ordinal) {
            i = (i + 1) and slotMask
            s = slots[i]
        }
        return s
    }

    /** Dense index of [ordinal], adding it first when absent. */
    fun add(ordinal: Int): Int {
        if ((size + 1) * 2 > slots.size) rehash(slots.size * 2)
        var i = mix(ordinal) and slotMask
        var s = slots[i]
        while (s != EMPTY && ordinals[s] != ordinal) {
            i = (i + 1) and slotMask
            s = slots[i]
        }
        if (s != EMPTY) return s
        if (size == ordinals.size) growDense()
        ordinals[size] = ordinal
        slots[i] = size
        return size++
    }

    /** Record that word [wordIdx] of entry [index]'s name starts with token [token]. */
    fun mark(index: Int, token: Int, wordIdx: Int) {
        val at = index * tokenCount + token
        masks[at] = masks[at] or wordBit(wordIdx)
    }

    /** Token [token]'s word-position mask for entry [index] (0: the token did not match). */
    fun mask(index: Int, token: Int): Long = masks[index * tokenCount + token]

    private fun growDense() {
        ordinals = ordinals.copyOf(ordinals.size * 2)
        masks = masks.copyOf(ordinals.size * tokenCount)
    }

    private fun rehash(capacity: Int) {
        slots = IntArray(capacity) { EMPTY }
        slotMask = capacity - 1
        for (d in 0 until size) {
            var i = mix(ordinals[d]) and slotMask
            while (slots[i] != EMPTY) i = (i + 1) and slotMask
            slots[i] = d
        }
    }

    private companion object {
        const val EMPTY = -1
        const val MIN_DENSE = 16
        const val MAX_WORD_BIT = 63

        /** Fibonacci hashing: ordinals are dense and clustered, so spread them first. */
        @Suppress("MagicNumber")
        fun mix(x: Int): Int = (x * -0x61c88647).let { it xor (it ushr 16) }

        fun capacityFor(expected: Int): Int {
            var c = MIN_DENSE
            while (c < expected * 2) c = c shl 1
            return c
        }

        /** Word positions past 63 share the top bit: still a match, never a phrase position. */
        fun wordBit(wordIdx: Int): Long = 1L shl minOf(wordIdx, MAX_WORD_BIT)
    }
}
