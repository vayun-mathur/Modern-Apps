package com.vayunmathur.games.hub.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.data.YuuUiModel
import com.vayunmathur.games.hub.data.dao.AchievementWithProgress
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.util.ProfileActions
import com.vayunmathur.games.hub.util.StreakCalculator
import com.vayunmathur.games.hub.util.XpLevelCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Immutable value describing cross game stats. */
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

/** View model for the game hub screen: holds UI state and handles its actions. */
class GameHubViewModel(
    application: Application,
    private val repository: GamesHubRepository,
) : AndroidViewModel(application), ProfileActions {

    val gamesFlow: StateFlow<List<HubGameEntity>> =
        repository.games.gamesFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalPlaytimeFromGamesFlow: StateFlow<Long> =
        repository.games.totalPlaytimeFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalPlaytimeSessionsFlow: StateFlow<Long> =
        repository.sessions.totalPlaytimeSessionsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalSessionsFlow: StateFlow<Int> =
        repository.games.totalSessionsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val allAchievementsFlow: StateFlow<List<AchievementWithProgress>> =
        repository.achievements.allWithProgressFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unlockedAchievementsFlow: StateFlow<List<AchievementWithProgress>> =
        repository.achievements.unlockedWithProgressFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalXpFlow: StateFlow<Int> =
        repository.achievements.totalXpFlow()
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
        repository.profile.profileFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val yuuListFlow: StateFlow<List<YuuUiModel>> =
        repository.yuus.yuusFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profileYuuFlow: StateFlow<YuuUiModel?> =
        repository.yuus.profileYuuFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = repository.profile.getProfile()
            if (existing == null) {
                repository.profile.upsertProfile(PlayerProfileEntity(displayName = "Player"))
            }
        }
    }

    override fun updateDisplayName(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.profile.getProfile() ?: PlayerProfileEntity()
            repository.profile.upsertProfile(current.copy(displayName = name))
        }
    }

    override fun updateAvatarSymbol(symbol: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.profile.getProfile() ?: PlayerProfileEntity()
            repository.profile.upsertProfile(current.copy(avatarSymbol = symbol))
        }
    }

    fun createYuu(name: String, config: YuuFaceConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.yuus.createYuu(name, config)
        }
    }

    fun updateYuu(id: Long, name: String, config: YuuFaceConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.yuus.updateYuu(id, name, config)
        }
    }

    fun duplicateYuu(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.yuus.duplicateYuu(id)
        }
    }

    fun deleteYuu(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.yuus.deleteYuu(id)
        }
    }

    fun setProfileYuu(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.yuus.setProfileYuu(id)
        }
    }

    fun clearProfileYuu() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.yuus.clearProfileYuu()
        }
    }

    override fun updateProfilePose(poseId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.yuus.getProfileYuu() ?: return@launch
            repository.yuus.updateYuu(current.id, current.name, current.config.copy(poseId = poseId))
        }
    }

    val sessionsFlow: StateFlow<List<PlaySessionEntity>> =
        repository.sessions.allSessionsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streakFlow: StateFlow<StreakCalculator.StreakResult> =
        sessionsFlow.map { StreakCalculator.calculate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StreakCalculator.StreakResult(0, 0))

    /** gameId -> (current, longest) daily-puzzle streak, as pushed by each game. */
    val dailyStreaksFlow: StateFlow<Map<String, Pair<Int, Int>>> =
        repository.streaks.allStreaksFlow()
            .map { streaks -> streaks.associate { it.gameId to (it.currentStreak to it.longestStreak) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val recentActivityFlow: StateFlow<List<ActivityEventEntity>> =
        repository.activity.recentActivityFlow(RECENT_ACTIVITY_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allActivityFlow: StateFlow<List<ActivityEventEntity>> =
        repository.activity.allActivityFlow()
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
            repository.achievements.totalDefsCountFlow()
        ) { stats, totalDefs ->
            stats.copy(totalAchievements = totalDefs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CrossGameStats())

    val statsFlow: StateFlow<CrossGameStats> = crossGameStatsFlowWithDefs

    private val _loadedGameIds = MutableStateFlow<Set<String>>(emptySet())

    /** Game ids whose DAO query has emitted at least once (a null game then means not found). */
    val loadedGameIds: StateFlow<Set<String>> = _loadedGameIds.asStateFlow()

    // Per-game flows
    fun getGameFlow(gameId: String): Flow<HubGameEntity?> = repository.games.gameByIdFlow(gameId)
        .onEach { _loadedGameIds.update { it + gameId } }

    fun getAchievementsForGameFlow(gameId: String): Flow<List<AchievementWithProgress>> =
        repository.achievements.byGameWithProgressFlow(gameId)

    fun getSessionsForGameFlow(gameId: String): Flow<List<PlaySessionEntity>> =
        repository.sessions.sessionsByGameFlow(gameId)

    fun getDailyStreakForGameFlow(gameId: String): Flow<DailyStreakEntity?> =
        repository.streaks.streakByGameFlow(gameId)

    fun getActivityForGameFlow(gameId: String): Flow<List<ActivityEventEntity>> =
        repository.activity.activityByGameFlow(gameId, GAME_ACTIVITY_LIMIT)

    /** Shared constants and helpers for GameHubViewModel. */
    companion object {
        private const val RECENT_ACTIVITY_LIMIT = 50
        private const val GAME_ACTIVITY_LIMIT = 30
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.games.clearGames()
            repository.achievements.clearDefs()
            repository.achievements.clearProgress()
            repository.sessions.clearSessions()
            repository.streaks.clearStreaks()
            repository.activity.clearActivities()
        }
    }
}

/** Creates game hub view model instances. */
class GameHubViewModelFactory(
    private val application: Application,
    private val repository: GamesHubRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(GameHubViewModel::class.java))
        return GameHubViewModel(application, repository) as T
    }
}
