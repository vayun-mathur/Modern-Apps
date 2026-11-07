package com.vayunmathur.games.hub.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.YUU_SKIN_TONES
import com.vayunmathur.games.hub.data.entities.YuuEntity
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.data.entities.decodeYuuConfig
import com.vayunmathur.games.hub.data.entities.encode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HubDatabaseTest {

    private fun db(): GamesHubDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GamesHubDatabase::class.java,
        ).setSingleConnectionPool().allowMainThreadQueries().build()

    @Test
    fun gameDaoCrudCountersAndOrdering() = runTest {
        val dao = db().gameDao()
        dao.upsert(HubGameEntity("chess", "com.chess", "Chess", lastPlayedAt = 100))
        dao.upsert(HubGameEntity("sudoku", "com.sudoku", "Sudoku", lastPlayedAt = 300))
        dao.upsert(HubGameEntity("mines", "com.mines", "Mines", lastPlayedAt = 200))
        assertEquals(listOf("sudoku", "mines", "chess"), dao.flowAll().first().map { it.gameId })
        assertEquals("Chess", dao.getById("chess")?.displayName)
        assertEquals("sudoku", dao.flowById("sudoku").first()?.gameId)
        dao.touchLastSeen("chess", 999)
        assertEquals(999, dao.getById("chess")?.lastSeenAt)
        dao.markPlayed("chess", 1000)
        dao.addPlaytime("chess", 5000)
        dao.addPlaytime("chess", 2500)
        val chess = dao.getById("chess")!!
        assertEquals(1000, chess.lastPlayedAt)
        assertEquals(1, chess.totalSessions)
        assertEquals(7500, chess.totalPlaytimeMs)
        assertEquals(7500, dao.flowTotalPlaytimeMs().first())
        assertEquals(1, dao.flowTotalSessions().first())
        dao.delete("mines")
        assertNull(dao.getById("mines"))
        dao.clearAll()
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun achievementDefsProgressJoinsAndXp() = runTest {
        val dao = db().achievementDao()
        dao.upsertDefs(
            listOf(
                AchievementDefEntity("chess", "first_win", "First Win", "win a game", xpReward = 50),
                AchievementDefEntity("chess", "master", "Master", "win 10", xpReward = 100, targetProgress = 10, isSecret = true, tier = "GOLD"),
                AchievementDefEntity("sudoku", "quick", "Quick", "solve fast", xpReward = 25),
            ),
        )
        dao.upsertProgresses(
            listOf(
                AchievementProgressEntity("chess", "first_win", progress = 1, isUnlocked = true, unlockedAt = 500),
                AchievementProgressEntity("chess", "master", progress = 4),
            ),
        )
        assertEquals(3, dao.flowTotalDefsCount().first())
        assertEquals(listOf("First Win", "Master"), dao.flowDefsByGame("chess").first().map { it.name })
        assertEquals(100, dao.getDef("chess", "master")?.xpReward)
        assertNull(dao.getDef("chess", "missing"))
        assertEquals(4, dao.getProgress("chess", "master")?.progress)
        // The join defaults progress to 0 / locked for defs the player hasn't touched.
        val joined = dao.flowByGameWithProgress("chess").first().associateBy { it.achievementId }
        assertEquals(1, joined["first_win"]?.progress)
        assertTrue(joined["first_win"]!!.isUnlocked)
        assertEquals(4, joined["master"]?.progress)
        assertEquals(50, dao.flowTotalXp().first()) // only unlocked achievements pay XP
        assertEquals(listOf("first_win"), dao.flowUnlockedWithProgress().first().map { it.achievementId })
        assertEquals(listOf("first_win"), dao.flowUnlockedProgress().first().map { it.achievementId })
        assertEquals(3, dao.flowAllWithProgress().first().size)
        assertEquals(2, dao.flowAllProgress().first().size)
        assertEquals(2, dao.flowProgressByGame("chess").first().size)
        dao.upsertProgress(AchievementProgressEntity("chess", "master", progress = 10, isUnlocked = true, unlockedAt = 900))
        assertEquals(150, dao.flowTotalXp().first())
        dao.clearProgress()
        assertEquals(0, dao.flowTotalXp().first())
        dao.clearDefs()
        assertEquals(0, dao.flowTotalDefsCount().first())
    }

    @Test
    fun sessionActivityProfileStreakAndYuuDaos() = runTest {
        val database = db()
        val sessions = database.sessionDao()
        sessions.upsert(PlaySessionEntity(gameId = "chess", sessionId = "s1", startTime = 100, endTime = 200, durationMs = 100))
        sessions.upsert(PlaySessionEntity(gameId = "chess", sessionId = "s2", startTime = 300))
        sessions.upsert(PlaySessionEntity(gameId = "sudoku", sessionId = "s3", startTime = 200, endTime = 500, durationMs = 300))
        assertEquals(listOf("s2", "s1"), sessions.flowByGame("chess").first().map { it.sessionId })
        assertEquals(400, sessions.flowTotalPlaytimeMs().first())
        assertEquals(100, sessions.flowPlaytimeByGame("chess").first())
        assertEquals(3, sessions.flowTotalCount().first())
        assertEquals(listOf("s2", "s3", "s1"), sessions.flowRecent(3).first().map { it.sessionId })
        sessions.endSession("s2", endTime = 450, durationMs = 150)
        assertEquals(150, sessions.getBySessionId("s2")?.durationMs)
        assertEquals(550, sessions.flowTotalPlaytimeMs().first())

        val activity = database.activityDao()
        repeat(5) { i ->
            activity.upsert(ActivityEventEntity(timestamp = 100L + i, type = "T$i", gameId = "chess", title = "Event $i"))
        }
        activity.upsert(ActivityEventEntity(timestamp = 999, type = ActivityEventEntity.TYPE_LEVEL_UP, gameId = "sudoku", title = "Level up"))
        assertEquals(3, activity.flowRecent(3).first().size)
        assertEquals(999, activity.flowRecent(1).first().single().timestamp)
        assertEquals(5, activity.flowByGame("chess", limit = 10).first().size)
        activity.trim(keep = 2)
        assertEquals(2, activity.flowAll().first().size)

        val profile = database.profileDao()
        assertNull(profile.getProfile())
        profile.upsert(PlayerProfileEntity(displayName = "Ada", title = "Master"))
        assertEquals("Ada", profile.flowProfile().first()?.displayName)
        profile.upsert(PlayerProfileEntity(displayName = "Ada Lovelace"))
        assertEquals("Ada Lovelace", profile.getProfile()?.displayName)

        val streaks = database.streakDao()
        streaks.upsert(DailyStreakEntity("wordle", currentStreak = 4, longestStreak = 9, lastCompletedDay = 100))
        streaks.upsert(DailyStreakEntity("mines", currentStreak = 1, longestStreak = 1, lastCompletedDay = 100))
        assertEquals(4, streaks.getByGame("wordle")?.currentStreak)
        assertEquals(2, streaks.flowAll().first().size)
        assertEquals(9, streaks.flowByGame("wordle").first()?.longestStreak)
        streaks.clearAll()
        assertTrue(streaks.flowAll().first().isEmpty())

        val yuus = database.yuuDao()
        val id1 = yuus.upsert(YuuEntity(name = "One", configJson = YuuFaceConfig(hairStyle = 2).encode()))
        val id2 = yuus.upsert(YuuEntity(name = "Two", configJson = ""))
        assertTrue(id1 > 0 && id2 > id1)
        assertEquals(2, yuus.getById(id1)?.hairStyleOf())
        yuus.setProfile(id2)
        assertEquals(id2, yuus.getProfile()?.id)
        assertEquals(id2, yuus.flowProfile().first()?.id)
        yuus.setProfile(id1)
        assertEquals(id1, yuus.getProfile()?.id)
        assertEquals(false, yuus.getById(id2)?.isProfile)
        yuus.clearProfileFlag()
        assertNull(yuus.getProfile())
        yuus.deleteById(id1)
        assertNull(yuus.getById(id1))
        yuus.clearAll()
        assertTrue(yuus.flowAll().first().isEmpty())
    }

    private fun YuuEntity.hairStyleOf(): Int = configJson.decodeYuuConfig().hairStyle

    @Test
    fun yuuConfigJsonRoundTripAndFallbacks() {
        val config = YuuFaceConfig(faceShape = 3, hairStyle = 7, eyeColor = 0xFF112233L, poseId = "wave")
        val decoded = config.encode().decodeYuuConfig()
        assertEquals(config, decoded)
        assertEquals(YuuFaceConfig.DEFAULT, "".decodeYuuConfig())
        assertEquals(YuuFaceConfig.DEFAULT, "   ".decodeYuuConfig())
        assertEquals(YuuFaceConfig.DEFAULT, "{bad json".decodeYuuConfig())
        // Unknown keys from a newer version are ignored, known ones survive.
        val future = """{"faceShape":2,"newPart":9}""".decodeYuuConfig()
        assertEquals(2, future.faceShape)
        assertEquals(8, YuuFaceConfig.FACE_SHAPES)
        assertEquals(12, YuuFaceConfig.HAIR_STYLES)
        assertContentEquals(listOf(0xFFFFE3D3L), YUU_SKIN_TONES.take(1))
    }
}
