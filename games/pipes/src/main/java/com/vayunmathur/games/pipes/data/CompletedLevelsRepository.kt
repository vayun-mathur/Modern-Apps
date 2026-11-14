package com.vayunmathur.games.pipes.data

import android.content.Context
import com.vayunmathur.library.util.LevelStatsRepository

/** Completed Levels Repository: a context implementation for completed levels repository. */
class CompletedLevelsRepository(context: Context) : LevelStatsRepository(context) {
    fun incrementTotalPipesPlaced() = incrementCounter("total_pipes")
    fun getTotalPipesPlaced(): Int = getCounter("total_pipes")
}
