package com.vayunmathur.games.kart.domain

import com.vayunmathur.games.kart.data.CUPS

/**
 * The garage rules, as pure functions on progress counters.
 *
 * There is no race engine yet, so this is deliberately thin: it decides which cup is on screen
 * and whether a cup index is playable. Physics, items and AI will grow their own files beside
 * this one rather than bloating it.
 */
object KartRules {

    /** The cup on screen for [cupIndex], wrapping past the end of the list. */
    fun cupAt(cupIndex: Int) = CUPS[cupIndex.mod(CUPS.size)]

    /** Whether [cupIndex] names a real cup rather than a pointer past the end. */
    fun isUnlocked(cupIndex: Int, cupsCleared: Int): Boolean = cupIndex <= cupsCleared

    /** The next cup after [cupIndex], or null once the last cup is cleared. */
    fun nextCup(cupIndex: Int): Int? =
        if (cupIndex + 1 < CUPS.size) cupIndex + 1 else null

    /**
     * Whether [winner] is a win for the local seat: 1st place in the final standings.
     *
     * Both race loops order standings by place/progress descending and pass the head of that
     * order as the winner, so a win is exactly `winner == localPlayerId`. A race with no
     * local seat (null id) or no winner is never a win.
     */
    fun isPlayerWin(winner: String?, localPlayerId: String?): Boolean =
        winner != null && localPlayerId != null && winner == localPlayerId
}
