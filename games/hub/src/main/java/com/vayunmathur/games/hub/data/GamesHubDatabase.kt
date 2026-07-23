package com.vayunmathur.games.hub.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vayunmathur.games.hub.data.dao.ActivityDao
import com.vayunmathur.games.hub.data.dao.AchievementDao
import com.vayunmathur.games.hub.data.dao.GameDao
import com.vayunmathur.games.hub.data.dao.ProfileDao
import com.vayunmathur.games.hub.data.dao.SessionDao
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity

const val DB_NAME = "games-hub-db"

@Database(
    entities = [
        HubGameEntity::class,
        AchievementDefEntity::class,
        AchievementProgressEntity::class,
        PlaySessionEntity::class,
        PlayerProfileEntity::class,
        ActivityEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class GamesHubDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun achievementDao(): AchievementDao
    abstract fun sessionDao(): SessionDao
    abstract fun profileDao(): ProfileDao
    abstract fun activityDao(): ActivityDao
}
