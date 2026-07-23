package com.vayunmathur.games.hub.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.hub.data.GamesHubDatabase
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.util.StreakCalculator
import com.vayunmathur.games.hub.util.XpLevelCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CrossGameStats(
    val totalPlaytimeMs: Long = 0L,
    val totalSessions: Int = 0,
    val totalGames: Int = 0,
    val totalAchievementsUnlocked: Int = 0,
    val totalAchievements: Int = 0,
    val totalXp: Int = 0,
    val level: Int = 1,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0
)

class GameHubViewModel(
    application: Application,
    private val db: GamesHubDatabase
) : AndroidViewModel(application) {

    val gamesFlow: StateFlow<List<HubGameEntity>> =
        db.gameDao().flowAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalPlaytimeFromGamesFlow: StateFlow<Long> =
        db.gameDao().flowTotalPlaytimeMs()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalPlaytimeSessionsFlow: StateFlow<Long> =
        db.sessionDao().flowTotalPlaytimeMs()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalSessionsFlow: StateFlow<Int> =
        db.gameDao().flowTotalSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val allAchievementsFlow: StateFlow<List<AchievementWithProgress>> =
        db.achievementDao().flowAllWithProgress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unlockedAchievementsFlow: StateFlow<List<AchievementWithProgress>> =
        db.achievementDao().flowUnlockedWithProgress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalXpFlow: StateFlow<Int> =
        db.achievementDao().flowTotalXp()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val levelFlow: StateFlow<Int> =
        totalXpFlow.map { XpLevelCalculator.level(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    val xpProgressFlow: StateFlow<Float> =
        totalXpFlow.map { XpLevelCalculator.progressToNextLevel(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val titleFlow: StateFlow<String> =
        levelFlow.map { XpLevelCalculator.title(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Beginner")

    val profileFlow: StateFlow<PlayerProfileEntity?> =
        db.profileDao().flowProfile()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.profileDao().getProfile()
            if (existing == null) {
                db.profileDao().upsert(PlayerProfileEntity(displayName = "Player"))
            }
        }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = db.profileDao().getProfile() ?: PlayerProfileEntity()
            db.profileDao().upsert(current.copy(displayName = name))
        }
    }

    fun updateAvatarSymbol(symbol: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = db.profileDao().getProfile() ?: PlayerProfileEntity()
            db.profileDao().upsert(current.copy(avatarSymbol = symbol))
        }
    }

    val sessionsFlow: StateFlow<List<PlaySessionEntity>> =
        db.sessionDao().flowAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streakFlow: StateFlow<StreakCalculator.StreakResult> =
        sessionsFlow.map { StreakCalculator.calculate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreakCalculator.StreakResult(0, 0))

    val recentActivityFlow: StateFlow<List<ActivityEventEntity>> =
        db.activityDao().flowRecent(50)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allActivityFlow: StateFlow<List<ActivityEventEntity>> =
        db.activityDao().flowAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Cross-game stats
    val crossGameStatsFlow: StateFlow<CrossGameStats> =
        combine(
            totalPlaytimeFromGamesFlow,
            totalPlaytimeSessionsFlow,
            totalSessionsFlow,
            gamesFlow,
            unlockedAchievementsFlow,
            totalXpFlow,
            levelFlow,
            streakFlow
        ) { args ->
            val fromGames = args[0] as Long
            val fromSessions = args[1] as Long
            val totalSessions = args[2] as Int
            @Suppress("UNCHECKED_CAST")
            val games = args[3] as List<HubGameEntity>
            @Suppress("UNCHECKED_CAST")
            val unlocked = args[4] as List<AchievementWithProgress>
            val xp = args[5] as Int
            val level = args[6] as Int
            val streak = args[7] as StreakCalculator.StreakResult

            CrossGameStats(
                totalPlaytimeMs = maxOf(fromGames, fromSessions),
                totalSessions = totalSessions,
                totalGames = games.size,
                totalAchievementsUnlocked = unlocked.size,
                totalAchievements = games.size,
                totalXp = xp,
                level = level,
                currentStreak = streak.currentStreak,
                longestStreak = streak.longestStreak
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CrossGameStats())

    val crossGameStatsFlowWithDefs: StateFlow<CrossGameStats> =
        combine(
            crossGameStatsFlow,
            db.achievementDao().flowTotalDefsCount()
        ) { stats, totalDefs ->
            stats.copy(totalAchievements = totalDefs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CrossGameStats())

    val statsFlow: StateFlow<CrossGameStats> = crossGameStatsFlowWithDefs

    // Per-game flows
    fun getGameFlow(gameId: String): Flow<HubGameEntity?> = db.gameDao().flowById(gameId)

    fun getAchievementsForGameFlow(gameId: String): Flow<List<AchievementWithProgress>> =
        db.achievementDao().flowByGameWithProgress(gameId)

    fun getSessionsForGameFlow(gameId: String): Flow<List<PlaySessionEntity>> =
        db.sessionDao().flowByGame(gameId)

    fun getActivityForGameFlow(gameId: String): Flow<List<ActivityEventEntity>> =
        db.activityDao().flowByGame(gameId, 30)

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            db.gameDao().clearAll()
            db.achievementDao().clearDefs()
            db.achievementDao().clearProgress()
            db.sessionDao().clearAll()
            db.activityDao().clearAll()
        }
    }
}

class GameHubViewModelFactory(
    private val application: Application,
    private val database: GamesHubDatabase
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GameHubViewModel::class.java))
        return GameHubViewModel(application, database) as T
    }
}
