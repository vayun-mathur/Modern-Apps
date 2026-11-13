package com.vayunmathur.games.hub.provider

import android.app.Application
import android.content.ContentValues
import android.provider.BaseColumns
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.hub.data.GamesHubDatabase
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.library.util.databases
import com.vayunmathur.sdk.games.GameHubContract
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HubProviderTest {

    companion object {
        private var sharedDb: GamesHubDatabase? = null
    }

    private lateinit var provider: GamesHubProvider
    private lateinit var repo: GamesHubRepository

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, GamesHubDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[GamesHubDatabase::class] = mem }
        repo = GamesHubRepository.get(app)
        provider = Robolectric.setupContentProvider(GamesHubProvider::class.java, GameHubContract.AUTHORITY)
    }

    private fun values(block: ContentValues.() -> Unit): ContentValues = ContentValues().apply(block)

    private fun cursorRows(cursor: android.database.Cursor?, column: String): List<String> {
        requireNotNull(cursor)
        return cursor.use { c ->
            val idx = c.getColumnIndex(column)
            buildList { while (c.moveToNext()) add(c.getString(idx)) }
        }
    }

    @Test
    fun gameRegistrationMergesAndReportsActivity() = runBlocking {
        val uri = GameHubContract.buildGameUri("chess")
        val inserted = provider.insert(
            uri,
            values {
                put(GameHubContract.Games.GAME_ID, "chess")
                put(GameHubContract.Games.PACKAGE_NAME, "com.chess")
                put(GameHubContract.Games.DISPLAY_NAME, "Chess")
                put(GameHubContract.Games.VERSION_CODE, 3L)
            },
        )
        assertEquals(uri, inserted)
        assertEquals("Chess", repo.games.getGame("chess")?.displayName)
        assertTrue(
            repo.activity.allActivityFlow().first()
                .any { it.type == ActivityEventEntity.TYPE_GAME_REGISTERED && it.gameId == "chess" },
        )
        // Re-registering with partial values merges onto the existing row.
        provider.insert(
            uri,
            values { put(GameHubContract.Games.DISPLAY_NAME, "Chess Deluxe") },
        )
        val merged = repo.games.getGame("chess")!!
        assertEquals("Chess Deluxe", merged.displayName)
        assertEquals("com.chess", merged.packageName)
        assertEquals(3L, merged.versionCode)
        val queried = provider.query(GameHubContract.buildGameUri("chess"), null, null, null, null)
        assertEquals(listOf("Chess Deluxe"), cursorRows(queried, GameHubContract.Games.DISPLAY_NAME))
        assertNull(provider.query(GameHubContract.buildGameUri("missing"), null, null, null, null)?.let {
            it.use { c -> if (c.count == 0) null else "row" }
        })
        assertNull(provider.getType(uri)) // the provider declares no MIME types
    }

    @Test
    fun achievementDefsProgressUnlocksAndQueries() = runBlocking {
        provider.insert(
            GameHubContract.buildAchievementDefsUri("chess"),
            values {
                put(GameHubContract.AchievementDefs.ACHIEVEMENT_ID, "win1")
                put(GameHubContract.AchievementDefs.NAME, "First Win")
                put(GameHubContract.AchievementDefs.DESCRIPTION, "Win a game")
                put(GameHubContract.AchievementDefs.XP_REWARD, 50)
            },
        )
        assertEquals("First Win", repo.achievements.getAchievementDef("chess", "win1")?.name)

        val progressUri = GameHubContract.buildAchievementProgressItemUri("chess", "win1")
        provider.insert(
            progressUri,
            values {
                put(GameHubContract.AchievementProgressCols.PROGRESS, 1)
                put(GameHubContract.AchievementProgressCols.IS_UNLOCKED, 1)
                put(GameHubContract.AchievementProgressCols.UNLOCKED_AT, 1234L)
            },
        )
        assertTrue(repo.achievements.getProgress("chess", "win1")!!.isUnlocked)
        assertTrue(
            repo.activity.allActivityFlow().first()
                .any { it.type == ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED && it.gameId == "chess" },
        )
        // Progress never regresses through the item merge.
        provider.insert(
            progressUri,
            values { put(GameHubContract.AchievementProgressCols.PROGRESS, 0) },
        )
        val kept = repo.achievements.getProgress("chess", "win1")!!
        assertEquals(1, kept.progress)
        assertTrue(kept.isUnlocked)
        assertEquals(1234L, kept.unlockedAt)

        assertEquals(
            listOf("First Win"),
            cursorRows(
                provider.query(GameHubContract.buildAchievementDefsUri("chess"), null, null, null, null),
                GameHubContract.AchievementDefs.NAME,
            ),
        )
        // Legacy achievements view joins defs with progress.
        val legacy = provider.query(GameHubContract.buildLegacyAchievementsUri("chess"), null, null, null, null)
        assertNotNull(legacy)
        legacy.use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(c.getColumnIndex(GameHubContract.Legacy.UNLOCKED)))
        }
        // Update path follows the same merge + unlock-activity rules.
        val updated = provider.update(
            GameHubContract.buildAchievementProgressItemUri("chess", "win2"),
            values {
                put(GameHubContract.AchievementProgressCols.PROGRESS, 1)
                put(GameHubContract.AchievementProgressCols.IS_UNLOCKED, 1)
            },
            null, null,
        )
        assertEquals(1, updated)
        assertTrue(repo.achievements.getProgress("chess", "win2")!!.isUnlocked)
        assertEquals(0, provider.update(GameHubContract.buildGameUri("nope/nope/x/y"), values { }, null, null))
        assertNull(provider.insert(GameHubContract.buildGameUri("x/y/z/w"), null))
        assertEquals(0, provider.delete(GameHubContract.buildGameUri("chess"), null, null))
    }

    @Test
    fun sessionsAndStreaksIngestWithPlaytimeAndBestProtection() = runBlocking {
        val sessionUri = GameHubContract.buildSessionItemUri("chess", "sess-1")
        provider.insert(
            sessionUri,
            values {
                put(GameHubContract.Sessions.SESSION_ID, "sess-1")
                put(GameHubContract.Sessions.START_TIME, 1_000_000L)
            },
        )
        assertNotNull(repo.sessions.getSessionById("sess-1"))
        assertEquals(1, repo.games.getGame("chess")?.totalSessions)
        val ended = provider.update(
            sessionUri,
            values { put(GameHubContract.Sessions.END_TIME, 1_060_000L) },
            null, null,
        )
        assertEquals(1, ended)
        assertEquals(60_000, repo.games.getGame("chess")?.totalPlaytimeMs)
        val completed = repo.activity.allActivityFlow().first()
            .filter { it.type == ActivityEventEntity.TYPE_SESSION_COMPLETED && it.gameId == "chess" }
        assertTrue(completed.isNotEmpty())
        // The feed title names the game, never the raw report id.
        val expectedName = com.vayunmathur.games.hub.domain.KnownGames
            .displayName("chess", repo.games.getGame("chess")?.displayName)
        assertTrue(completed.all { it.title == "Played $expectedName" }, completed.joinToString { it.title })
        // Ending an unknown session is a no-op.
        assertEquals(
            0,
            provider.update(GameHubContract.buildSessionItemUri("chess", "ghost"), values { }, null, null),
        )

        val streakUri = GameHubContract.buildStreakUri("wordle")
        provider.insert(
            streakUri,
            values {
                put(GameHubContract.Streaks.CURRENT_STREAK, 4)
                put(GameHubContract.Streaks.LONGEST_STREAK, 9)
                put(GameHubContract.Streaks.LAST_COMPLETED_DAY, 100L)
            },
        )
        assertEquals(9, repo.streaks.getStreak("wordle")?.longestStreak)
        // A fresh reinstall reporting a lower best must not lower the stored one.
        provider.insert(
            streakUri,
            values {
                put(GameHubContract.Streaks.CURRENT_STREAK, 1)
                put(GameHubContract.Streaks.LONGEST_STREAK, 2)
                put(GameHubContract.Streaks.LAST_COMPLETED_DAY, 101L)
            },
        )
        val streak = repo.streaks.getStreak("wordle")!!
        assertEquals(1, streak.currentStreak)
        assertEquals(9, streak.longestStreak)
        assertEquals(listOf("9"),
            cursorRows(provider.query(streakUri, null, null, null, null), GameHubContract.Streaks.LONGEST_STREAK))
    }

    @Test
    fun convertersPreferContractColumnsAndLegacyItemEnsuresDef() = runBlocking {
        // Legacy per-item insert (achievements/*/*) creates a placeholder def.
        val legacyItem = GameHubContract.buildLegacyAchievementItemUri("sudoku", "fast-solver")
        provider.insert(
            legacyItem,
            values {
                put("progress", 1)
                put("unlocked", 1)
            },
        )
        val def = repo.achievements.getAchievementDef("sudoku", "fast-solver")
        assertNotNull(def)
        assertEquals("fast-solver", def.name)
        assertEquals(25, def.xpReward)
        val queried = provider.query(legacyItem, null, null, null, null)
        assertNotNull(queried)
        queried.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("fast-solver", c.getString(c.getColumnIndex(GameHubContract.Legacy.ACHIEVEMENT_ID)))
        }
        assertEquals(BaseColumns._ID, "_id")
    }
}
