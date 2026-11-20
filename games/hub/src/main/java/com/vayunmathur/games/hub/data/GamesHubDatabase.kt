package com.vayunmathur.games.hub.data

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.games.hub.data.dao.ActivityDao
import com.vayunmathur.games.hub.data.dao.AchievementDao
import com.vayunmathur.games.hub.data.dao.GameDao
import com.vayunmathur.games.hub.data.dao.ProfileDao
import com.vayunmathur.games.hub.data.dao.SessionDao
import com.vayunmathur.games.hub.data.dao.StreakDao
import com.vayunmathur.games.hub.data.dao.YuuDao
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.games.hub.data.entities.YuuEntity
import com.vayunmathur.library.util.DatabaseMigrations

const val DB_NAME = "games-hub-db"

@Database(
    entities = [
        HubGameEntity::class,
        AchievementDefEntity::class,
        AchievementProgressEntity::class,
        PlaySessionEntity::class,
        PlayerProfileEntity::class,
        ActivityEventEntity::class,
        DailyStreakEntity::class,
        YuuEntity::class
    ],
    version = 3,
    exportSchema = true
)
/** Games Hub Database: a room database implementation for games hub database. */
abstract class GamesHubDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun achievementDao(): AchievementDao
    abstract fun sessionDao(): SessionDao
    abstract fun profileDao(): ProfileDao
    abstract fun activityDao(): ActivityDao
    abstract fun streakDao(): StreakDao
    abstract fun yuuDao(): YuuDao

    /** Shared constants and helpers for GamesHubDatabase. */
    companion object : DatabaseMigrations {
        override val migrations: List<Migration> = listOf(
            // Per-game daily-puzzle streaks. Schemas aren't exported here, so this DDL is written
            // to byte-match what Room generates for DailyStreakEntity — Room validates it on open.
            Migration(1, 2) { db ->
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_streaks` (" +
                        "`gameId` TEXT NOT NULL, " +
                        "`currentStreak` INTEGER NOT NULL, " +
                        "`longestStreak` INTEGER NOT NULL, " +
                        "`lastCompletedDay` INTEGER NOT NULL, " +
                        "`lastUpdated` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`gameId`))",
                )
            },
            // Yuu avatar plaza. DDL byte-matches Room's generated schema for YuuEntity
            // (autoGenerate id, TEXT name/configJson, INTEGER updatedAt/isProfile).
            Migration(2, 3) { db ->
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `yuus` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`configJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`isProfile` INTEGER NOT NULL)",
                )
            },
        )
    }
}
