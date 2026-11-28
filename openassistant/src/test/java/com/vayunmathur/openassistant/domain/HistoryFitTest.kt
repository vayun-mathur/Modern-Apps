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

    // #929: a turn's image parts are part of the turn. Fitting must keep them
    // on the turns it keeps — trimming may drop whole turns, never strip a
    // kept turn down to text only.
    private data class ImgTurn(val user: Boolean, val cost: Int, val images: List<String>)

    @Test
    fun imagePartsSurviveFittingOnKeptTurns() {
        val imgHistory = listOf(
            ImgTurn(true, 10, listOf("/photos/first.jpg")),
            ImgTurn(false, 10, emptyList()),
            ImgTurn(true, 10, listOf("/photos/second.jpg")),
            ImgTurn(false, 10, emptyList()),
        )
        val imgCurrent = ImgTurn(true, 5, listOf("/photos/current.jpg"))
        assertEquals(
            imgHistory + imgCurrent,
            fitConversation(imgHistory, imgCurrent, ImgTurn::user, { turns -> turns.sumOf { it.cost } <= 100 }),
        )
        // Trimmed to the newest user turn + current: both keep their images.
        assertEquals(
            listOf(imgHistory[2], imgHistory[3], imgCurrent),
            fitConversation(imgHistory, imgCurrent, ImgTurn::user, { turns -> turns.sumOf { it.cost } <= 30 }),
        )
    }

    @Test
    fun firstMessageWithImageKeepsItsImage() {
        // First message of a conversation: no history, the attached photo is
        // on the current turn and must reach the model with its text.
        val imgCurrent = ImgTurn(true, 5, listOf("/photos/first.jpg"))
        val fitted = fitConversation(emptyList(), imgCurrent, ImgTurn::user, { turns -> turns.sumOf { it.cost } <= 5 })
        assertEquals(listOf(imgCurrent), fitted)
        assertEquals(listOf("/photos/first.jpg"), fitted!!.single().images)
    }

    @Test
    fun currentImageTurnIsNeverStrippedWhenHistoryIsTrimmedAway() {
        val imgHistory = listOf(ImgTurn(true, 10, listOf("/photos/old.jpg")), ImgTurn(false, 10, emptyList()))
        val imgCurrent = ImgTurn(true, 5, listOf("/photos/current.jpg"))
        val fitted = fitConversation(imgHistory, imgCurrent, ImgTurn::user, { turns -> turns.sumOf { it.cost } <= 5 })
        assertEquals(listOf(imgCurrent), fitted)
        assertEquals(listOf("/photos/current.jpg"), fitted!!.single().images)
    }
}
