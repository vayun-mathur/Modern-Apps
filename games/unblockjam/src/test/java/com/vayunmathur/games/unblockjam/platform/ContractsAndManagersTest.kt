package com.vayunmathur.games.unblockjam.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.unblockjam.Route
import com.vayunmathur.games.unblockjam.data.CompletedLevelsRepository
import com.vayunmathur.games.unblockjam.data.LevelPack
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ContractsAndManagersTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context

    @BeforeTest
    fun setUp() {
        if (LevelPack.PACKS.isEmpty()) LevelPack.init(app)
    }

    @Test
    fun gameActionsNoopDefaultsDoNothing() {
        val noop = GameActions.Noop
        noop.onBlockMoved(LevelPack.PACKS[0].levels[0])
        noop.onLevelWon(); noop.onUndo(); noop.onRestart(); noop.onLevelChange(3)
        // reaching here without state is the assertion; also a custom impl records
        var changed: Int? = null
        val custom = object : GameActions {
            override fun onLevelChange(newIndex: Int) { changed = newIndex }
        }
        custom.onLevelChange(7)
        assertEquals(7, changed)
        // other defaults remain no-ops on the custom impl
        custom.onUndo(); custom.onRestart(); custom.onLevelWon()
    }

    @Test
    fun settingsActionsNoopDefaultsDoNothing() {
        val noop = SettingsActions.Noop
        noop.setReminderEnabled(true)
        noop.setReminderTime(1, 2)
        var seen: Pair<Boolean, Pair<Int, Int>>? = null
        val custom = object : SettingsActions {
            override fun setReminderEnabled(enabled: Boolean) { seen = enabled to (0 to 0) }
        }
        custom.setReminderEnabled(false)
        assertEquals(false, seen!!.first)
        custom.setReminderTime(3, 4) // default no-op
    }

    @Test
    fun uiStateDataClasses() {
        val level = LevelPack.PACKS[0].levels[0]
        val state = GameUiState(levelData = level)
        assertEquals(0, state.levelIndex)
        assertEquals(0, state.moves)
        assertEquals(null, state.bestScore)
        assertFalse(state.isCompleted)
        assertFalse(state.isLevelWon)
        assertFalse(state.canUndo)
        assertEquals(state, state.copy())
        assertFalse(state == state.copy(moves = 1))

        val daily = DailyProgress(day = 10, completed = 1, total = 5, streak = 2)
        assertEquals(10L, daily.day)
        assertEquals(daily, daily.copy(completed = 1))

        val settings = SettingsUiState()
        assertFalse(settings.reminderEnabled)
        assertEquals(20, settings.reminderHour)
        assertEquals(0, settings.reminderMinute)
        assertEquals(settings, settings.copy(reminderHour = 20))
    }

    @Test
    fun completedLevelsRepositoryCountersPersist() {
        val repo = CompletedLevelsRepository(ctx)
        val movesBefore = repo.getTotalMoves()
        repo.incrementTotalMoves(); repo.incrementTotalMoves()
        assertEquals(movesBefore + 2, repo.getTotalMoves())
        // fresh instance over same prefs sees the same counters
        val repo2 = CompletedLevelsRepository(ctx)
        assertEquals(movesBefore + 2, repo2.getTotalMoves())
        val undoBefore = repo.getUndoCount()
        repo.incrementUndoCount()
        assertEquals(undoBefore + 1, repo2.getUndoCount())
    }

    @Test
    fun achievementsManagerChecksExistingAndPackCompletion() {
        val repo = CompletedLevelsRepository(ctx)
        val json = ctx.assets.open("achievements.json").bufferedReader().use { it.readText() }
        val manager = UnblockJamAchievementsManager(ctx, json, repo)
        assertEquals(11, manager.achievements.size)
        assertTrue(manager.achievements.any { it.id == "first_level" })
        manager.checkExistingAchievements()
        // completing every level of pack 0 in the completed set: progress call must not crash
        val allPack0 = LevelPack.PACKS[0].levels.map { it.id }.toSet()
        manager.onPackCompletionChanged(allPack0)
        manager.onPackCompletionChanged(emptySet())
        // partial: one level from a wall pack and one from a size pack
        val partial = setOf(
            LevelPack.PACKS[LevelPack.WALL_PACK_INDICES.first()].levels.first().id,
            LevelPack.PACKS[LevelPack.SIZE_PACK_INDICES.first()].levels.first().id,
            "nonexistent_id",
        )
        manager.onPackCompletionChanged(partial)
        manager.dismissNotification()
        assertEquals(null, manager.newAchievement.value)
    }

    @Test
    fun appBackupAgentListsPrefsAndDatastores() {
        val agent = AppBackupAgent()
        assertEquals(listOf("level_stats", "daily_stats"), agent.prefNames)
        assertEquals(listOf("datastore_default"), agent.datastoreNames)
    }

    @Test
    fun routesSerializeRoundTrip() {
        val json = Json
        fun <T> roundTrip(value: T, serializer: kotlinx.serialization.KSerializer<T>) {
            val encoded = json.encodeToString(serializer, value)
            assertEquals(value, json.decodeFromString(serializer, encoded))
        }
        roundTrip(Route.PackSelector as Route, Route.serializer())
        roundTrip(Route.LevelSelector(3) as Route, Route.serializer())
        roundTrip(Route.Game(1, 2) as Route, Route.serializer())
        roundTrip(Route.DailySelector as Route, Route.serializer())
        roundTrip(Route.DailyGame(4) as Route, Route.serializer())
        roundTrip(Route.GameCenter as Route, Route.serializer())
        roundTrip(Route.Settings as Route, Route.serializer())
        assertEquals(3, Route.LevelSelector(3).packIndex)
        assertEquals(2, Route.Game(1, 2).levelIndex)
        assertEquals(4, Route.DailyGame(4).levelIndex)
        assertNotNull(Route.PackSelector)
    }
}
