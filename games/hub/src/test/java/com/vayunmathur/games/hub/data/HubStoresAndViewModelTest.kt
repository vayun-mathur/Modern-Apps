package com.vayunmathur.games.hub.data

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.games.hub.viewmodel.GameHubViewModelFactory
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HubStoresAndViewModelTest {

    companion object {
        private var sharedDb: GamesHubDatabase? = null
    }

    private lateinit var app: Application
    private lateinit var repo: GamesHubRepository
    private lateinit var vm: GameHubViewModel

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, GamesHubDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[GamesHubDatabase::class] = mem }
        repo = GamesHubRepository.get(app)
        vm = GameHubViewModel(app, repo)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch { vm.gamesFlow.collect {} }
        scope.launch { vm.totalPlaytimeFromGamesFlow.collect {} }
        scope.launch { vm.totalPlaytimeSessionsFlow.collect {} }
        scope.launch { vm.totalSessionsFlow.collect {} }
        scope.launch { vm.allAchievementsFlow.collect {} }
        scope.launch { vm.unlockedAchievementsFlow.collect {} }
        scope.launch { vm.totalXpFlow.collect {} }
        scope.launch { vm.levelFlow.collect {} }
        scope.launch { vm.xpProgressFlow.collect {} }
        scope.launch { vm.titleFlow.collect {} }
        scope.launch { vm.profileFlow.collect {} }
        scope.launch { vm.yuuListFlow.collect {} }
        scope.launch { vm.profileYuuFlow.collect {} }
        scope.launch { vm.sessionsFlow.collect {} }
        scope.launch { vm.streakFlow.collect {} }
        scope.launch { vm.dailyStreaksFlow.collect {} }
        scope.launch { vm.recentActivityFlow.collect {} }
        scope.launch { vm.allActivityFlow.collect {} }
        scope.launch { vm.crossGameStatsFlow.collect {} }
        scope.launch { vm.statsFlow.collect {} }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50)
        }
        error("timed out waiting for $label")
    }

    @Test
    fun storesRoundTripThroughRepository() = runBlocking {
        repo.games.upsertGame(HubGameEntity("chess", "com.chess", "Chess"))
        repo.games.markPlayed("chess", 500)
        repo.games.addPlaytime("chess", 60_000)
        assertEquals("Chess", repo.games.getGame("chess")?.displayName)
        assertEquals(1, repo.games.totalSessionsFlow().first())
        assertEquals(60_000, repo.games.totalPlaytimeFlow().first())
        assertEquals(listOf("chess"), repo.games.gamesFlow().first().map { it.gameId })
        assertEquals("Chess", repo.games.gameByIdFlow("chess").first()?.displayName)

        repo.achievements.upsertDef(AchievementDefEntity("chess", "win1", "First Win", "d", xpReward = 50))
        repo.achievements.upsertProgress(AchievementProgressEntity("chess", "win1", progress = 1, isUnlocked = true, unlockedAt = 1))
        assertEquals(50, repo.achievements.totalXpFlow().first())
        assertEquals(1, repo.achievements.unlockedWithProgressFlow().first().size)
        assertEquals(1, repo.achievements.allWithProgressFlow().first().size)
        assertEquals(1, repo.achievements.byGameWithProgressFlow("chess").first().size)
        assertEquals(1, repo.achievements.totalDefsCountFlow().first())

        repo.sessions.upsertSession(PlaySessionEntity(gameId = "chess", sessionId = "s1", startTime = 100))
        repo.sessions.endSession("s1", endTime = 200, durationMs = 100)
        assertEquals(100, repo.sessions.totalPlaytimeSessionsFlow().first())
        assertEquals("s1", repo.sessions.getSessionById("s1")?.sessionId)
        assertEquals(1, repo.sessions.sessionsByGameFlow("chess").first().size)

        repo.streaks.upsertStreak(DailyStreakEntity("wordle", currentStreak = 3, longestStreak = 5, lastCompletedDay = 10))
        assertEquals(3, repo.streaks.getStreak("wordle")?.currentStreak)
        assertEquals(5, repo.streaks.streakByGameFlow("wordle").first()?.longestStreak)
        assertEquals(1, repo.streaks.allStreaksFlow().first().size)

        repo.activity.upsertActivity(ActivityEventEntity(type = "T", gameId = "chess", title = "Hello"))
        assertEquals("Hello", repo.activity.recentActivityFlow(5).first().single().title)
        assertEquals(1, repo.activity.activityByGameFlow("chess").first().size)
        assertEquals(1, repo.activity.allActivityFlow().first().size)
        assertEquals("games-hub-db", DB_NAME)
    }

    @Test
    fun profileSeedingAndViewModelProfileEdits() {
        await("profile seeded") { runBlocking { repo.profile.getProfile() } != null }
        assertEquals("Player", vm.profileFlow.value?.displayName)
        vm.updateDisplayName("Ada")
        await("name updated") { runBlocking { repo.profile.getProfile() }?.displayName == "Ada" }
        vm.updateAvatarSymbol("star")
        await("avatar updated") { runBlocking { repo.profile.getProfile() }?.avatarSymbol == "star" }
        val current = runBlocking { repo.profile.getProfile() }!!
        assertTrue(current.createdAt > 0)
    }

    @Test
    fun viewModelDerivedFlowsFollowXp() {
        runBlocking {
            repo.achievements.upsertDef(AchievementDefEntity("chess", "big", "Big", "d", xpReward = 150))
            repo.achievements.upsertProgress(AchievementProgressEntity("chess", "big", progress = 1, isUnlocked = true, unlockedAt = 1))
            repo.games.upsertGame(HubGameEntity("chess2", "com.chess", "Chess 2"))
        }
        await("xp in vm") { vm.totalXpFlow.value >= 150 }
        assertEquals(2, vm.levelFlow.value)
        assertTrue(vm.xpProgressFlow.value in 0f..1f)
        assertEquals("Novice", vm.titleFlow.value)
        assertTrue(vm.allAchievementsFlow.value.any { it.achievementId == "big" })
        assertTrue(vm.unlockedAchievementsFlow.value.any { it.achievementId == "big" })
        assertTrue(vm.gamesFlow.value.any { it.gameId == "chess2" })
    }

    @Test
    fun yuuCrudThroughViewModelAndStore() {
        vm.createYuu("Mii One", YuuFaceConfig(hairStyle = 4))
        await("yuu created") { runBlocking { repo.yuus.yuusFlow().first() }.any { it.name == "Mii One" } }
        val created = runBlocking { repo.yuus.yuusFlow().first().first { it.name == "Mii One" } }
        assertEquals(4, created.config.hairStyle)
        vm.updateYuu(created.id, "Mii Renamed", YuuFaceConfig(hairStyle = 9))
        await("yuu renamed") { runBlocking { repo.yuus.getYuu(created.id) }?.name == "Mii Renamed" }
        vm.duplicateYuu(created.id)
        await("yuu duplicated") { runBlocking { repo.yuus.yuusFlow().first() }.any { it.name == "Mii Renamed copy" } }
        val copy = runBlocking { repo.yuus.yuusFlow().first().first { it.name.endsWith("copy") } }
        vm.setProfileYuu(copy.id)
        await("profile yuu") { runBlocking { repo.yuus.getProfileYuu() }?.id == copy.id }
        assertEquals(copy.id, vm.profileYuuFlow.value?.id ?: copy.id)
        vm.deleteYuu(copy.id)
        await("yuu deleted") { runBlocking { repo.yuus.getYuu(copy.id) } == null }
        // Store-level edge cases.
        runBlocking {
            assertNull(repo.yuus.getYuu(999_999))
            assertNull(repo.yuus.duplicateYuu(999_999))
            repo.yuus.updateYuu(999_999, "ghost", YuuFaceConfig()) // no-op
            repo.yuus.clearProfileYuu()
            assertNull(repo.yuus.getProfileYuu())
        }
    }

    @Test
    fun clearOperationsEmptyTheStores() = runBlocking {
        repo.games.upsertGame(HubGameEntity("todelete", "com.x", "X"))
        repo.sessions.upsertSession(PlaySessionEntity(gameId = "todelete", sessionId = "sd", startTime = 1))
        repo.games.clearGames()
        repo.sessions.clearSessions()
        repo.achievements.clearDefs()
        repo.achievements.clearProgress()
        repo.streaks.clearStreaks()
        repo.activity.clearActivities()
        repo.yuus.clearYuus()
        assertTrue(repo.games.getAllGames().none { it.gameId == "todelete" })
        assertTrue(repo.sessions.allSessionsFlow().first().isEmpty())
        assertTrue(repo.yuus.yuusFlow().first().isEmpty())
        val database = repo.database
        assertEquals("games-hub-db", DB_NAME)
        assertTrue(database == repo.database)
    }

    @Test
    fun statsAndFactory() {
        runBlocking {
        repo.games.upsertGame(HubGameEntity("stats-game", "com.s", "Stats"))
        repo.profile.upsertProfile(PlayerProfileEntity(displayName = "Stats Player"))
        await("stats game visible") { vm.gamesFlow.value.any { it.gameId == "stats-game" } }
        // Per-game flows and stats derivation.
        assertEquals("chess", runBlocking { vm.getGameFlow("chess").first()?.gameId })
        assertTrue(runBlocking { vm.getAchievementsForGameFlow("chess").first().isNotEmpty() })
        await("stats flow") { vm.statsFlow.value.totalGames >= 1 }
        assertEquals(vm.statsFlow.value.totalGames, vm.crossGameStatsFlow.value.totalGames)
        val factory = GameHubViewModelFactory(app, repo)
        assertEquals(GameHubViewModel::class.java, factory.create(GameHubViewModel::class.java).javaClass)
        assertFailsWith<IllegalArgumentException> { factory.create(MainViewModelStub::class.java) }
        }
    }

    class MainViewModelStub : androidx.lifecycle.ViewModel()
}
