package com.vayunmathur.games.unblockjam.data

import android.content.Context
import com.vayunmathur.library.util.LevelStatsRepository

/** Persistent level statistics and move/undo counters for Unblock Jam. */
class CompletedLevelsRepository(context: Context) : LevelStatsRepository(context) {
    fun incrementTotalMoves() = incrementCounter("total_moves")
    fun getTotalMoves(): Int = getCounter("total_moves")

    fun incrementUndoCount() = incrementCounter("undo_count")
    fun getUndoCount(): Int = getCounter("undo_count")
}
