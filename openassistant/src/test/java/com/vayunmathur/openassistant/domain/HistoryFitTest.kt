package com.vayunmathur.openassistant.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryFitTest {

    private data class T(val user: Boolean, val cost: Int)

    private fun fitsUnder(budget: Int): (List<T>) -> Boolean = { turns -> turns.sumOf { it.cost } <= budget }

    private val history = listOf(T(true, 10), T(false, 10), T(true, 10), T(false, 10))
    private val current = T(true, 5)

    @Test
    fun keepsEverythingWhenItFits() {
        assertEquals(history + current, fitConversation(history, current, T::user, fitsUnder(100)))
    }

    @Test
    fun dropsOldestTurnsAndStartsOnAUserTurn() {
        assertEquals(listOf(T(true, 10), T(false, 10), current), fitConversation(history, current, T::user, fitsUnder(30)))
        // 35 fits three turns, but the oldest kept would be a model turn, so it goes too.
        assertEquals(listOf(T(true, 10), T(false, 10), current), fitConversation(history, current, T::user, fitsUnder(35)))
    }

    @Test
    fun neverDropsTheCurrentTurn() {
        assertEquals(listOf(current), fitConversation(history, current, T::user, fitsUnder(5)))
        assertNull(fitConversation(history, current, T::user, fitsUnder(4)))
    }

    @Test
    fun emptyHistory() {
        assertEquals(listOf(current), fitConversation(emptyList(), current, T::user, fitsUnder(5)))
    }
}
