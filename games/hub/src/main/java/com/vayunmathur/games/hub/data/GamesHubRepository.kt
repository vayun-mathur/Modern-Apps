package com.vayunmathur.games.hub.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [GamesHubDatabase].
 *
 * Per-domain reads and writes live on the store classes ([GameStore], [AchievementStore],
 * [SessionStore], [StreakStore], [ProfileStore], [ActivityStore], [YuuStore]); this class keeps the
 * singleton and the database handle.
 */
class GamesHubRepository private constructor(context: Context) :
    RoomRepository<GamesHubDatabase>(context, GamesHubDatabase::class, DB_NAME) {

    internal fun gameDaoAccess() = db.gameDao()
    internal fun achievementDaoAccess() = db.achievementDao()
    internal fun sessionDaoAccess() = db.sessionDao()
    internal fun profileDaoAccess() = db.profileDao()
    internal fun activityDaoAccess() = db.activityDao()
    internal fun streakDaoAccess() = db.streakDao()
    internal fun yuuDaoAccess() = db.yuuDao()

    val games: GameStore = GameStore(this)
    val achievements: AchievementStore = AchievementStore(this)
    val sessions: SessionStore = SessionStore(this)
    val streaks: StreakStore = StreakStore(this)
    val profile: ProfileStore = ProfileStore(this)
    val activity: ActivityStore = ActivityStore(this)
    val yuus: YuuStore = YuuStore(this)

    val database: GamesHubDatabase get() = db

    /** Shared constants and helpers for GamesHubRepository. */
    companion object {
        @Volatile
        private var instance: GamesHubRepository? = null

        fun get(context: Context): GamesHubRepository =
            instance ?: synchronized(this) {
                instance ?: GamesHubRepository(context).also { instance = it }
            }
    }
}
