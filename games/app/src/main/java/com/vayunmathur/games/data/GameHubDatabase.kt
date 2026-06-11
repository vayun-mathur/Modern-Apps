package com.vayunmathur.games.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AchievementEntity::class, ScoreEntity::class], version = 1)
abstract class GameHubDatabase : RoomDatabase() {
    abstract fun achievementDao(): AchievementDao
    abstract fun scoreDao(): ScoreDao
}
