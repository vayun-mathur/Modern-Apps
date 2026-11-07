package com.vayunmathur.library.util

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/** Converters, MatchingDao, LevelStats, DailyChallenge, Achievements, AppMessages. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LibraryState2Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class TestAchievements(context: Context, prefix: String) : AchievementsManager(
        context,
        """[{"id":"first_win","name":"First Win","description":"win once","targetProgress":3},
            {"id":"win_10","name":"Ten","description":"win ten","targetProgress":10,"isSecret":true}]""",
        prefix,
    ) {
        var checked = false
        override fun checkExistingAchievements() { checked = true }
    }

    private class TestLevelStats(context: Context, name: String) : LevelStatsRepository(context, name) {
        fun bump(key: String) = incrementCounter(key)
        fun count(key: String) = getCounter(key)
    }

    @Test fun defaultConvertersRoundTrip() {
        val c = DefaultConverters()
        val instant = Instant.fromEpochSeconds(1_700_000_000)
        assertEquals(instant, c.toInstant(c.fromInstant(instant)))
        assertNull(c.fromInstantNullable(null)); assertNull(c.toInstantNullable(null))
        assertEquals(instant, c.toInstantNullable(c.fromInstantNullable(instant)))
        assertEquals(listOf(1L, 2L), c.toList(c.fromList(listOf(1L, 2L))))
        assertNull(c.fromList(null)); assertNull(c.toList(null))
        assertEquals(listOf("a", "b"), c.toListS(c.fromListS(listOf("a", "b"))))
        assertEquals(1500L, c.fromDuration(1500.milliseconds))
        assertEquals(1500.milliseconds, c.toDuration(1500L))
        assertNull(c.fromDurationNullable(null)); assertNull(c.toDurationNullable(null))
        assertEquals(LocalTime(13, 30, 15), c.toLocalTime(c.fromLocalTime(LocalTime(13, 30, 15))))
    }

    @Test fun matchingDaoCrudAndDirections() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, LibraryState2TestDb::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.matchingDao()
            val id = dao.upsert(ManyManyMatching(leftID = 1, rightID = 10, type = 7))
            assertTrue(id > 0)
            dao.upsert(listOf(ManyManyMatching(1, 11, 7), ManyManyMatching(2, 10, 7), ManyManyMatching(1, 10, 8)))
            assertEquals(listOf(10L, 11L), dao.getFromLeft(1, 7).sorted())
            assertEquals(listOf(1L, 2L), dao.getFromRight(10, 7).sorted())
            assertEquals(3, dao.flow().first().count { it.type == 7 })
            dao.deleteMatch(1, 10, 7)
            assertEquals(listOf(11L), dao.getFromLeft(1, 7))
            dao.deleteFromRight(10, 7)
            assertEquals(emptyList(), dao.getFromRight(10, 7))
            dao.deleteFromLeft(1, 7)
            assertEquals(emptyList(), dao.getFromLeft(1, 7))
            // Remaining row is type 8; deleteByType then clear.
            dao.deleteByType(8)
            assertTrue(dao.flow().first().isEmpty())
            val row = ManyManyMatching(5, 6, 1)
            dao.upsert(row)
            val stored = dao.flow().first().single()
            assertEquals(1, dao.delete(stored))
            closeCachedDatabase<LibraryState2TestDb>() // no cached db: must be a safe no-op
            databases[LibraryState2TestDb::class] = db
            closeCachedDatabase<LibraryState2TestDb>()
            assertFalse(databases.containsKey(LibraryState2TestDb::class))
        } finally {
            runCatching { db.close() }
        }
    }

    @Test fun levelStatsKeepsLowestAndRetainsOnly() {
        val repo = TestLevelStats(ctx, "test_levels_${System.nanoTime()}")
        assertEquals(emptyMap(), repo.getLevelStats())
        repo.updateBestScore("lvl1", 100)
        assertEquals(100, repo.getLevelStats()["lvl1"]?.bestScore)
        repo.updateBestScore("lvl1", 150) // worse (higher) is ignored
        assertEquals(100, repo.getLevelStats()["lvl1"]?.bestScore)
        repo.updateBestScore("lvl1", 80)
        assertEquals(80, repo.getLevelStats()["lvl1"]?.bestScore)
        repo.updateBestScore("lvl2", 50)
        repo.retainOnly(setOf("lvl2"))
        assertEquals(setOf("lvl2"), repo.getLevelStats().keys)
        repo.retainOnly(setOf("lvl2")) // no-op, same size
        assertEquals(50, repo.getLevelStats()["lvl2"]?.bestScore)
        assertEquals(LevelStats(50), repo.getLevelStats()["lvl2"])
        repo.bump("plays"); repo.bump("plays")
        assertEquals(2, repo.count("plays"))
        assertEquals(0, repo.count("never"))
    }

    @Test fun dailyChallengeStreakProgression() = runBlocking {
        val store = DailyChallengeStore(ctx, "dc${System.nanoTime()}")
        assertEquals(Long.MIN_VALUE, store.lastCompletedDay())
        assertEquals(0L, store.bestStreak())
        val day = store.todayEpochDay()
        assertTrue(day > 0)
        val first = store.recordDayCompleted(day)
        assertEquals(DailyChallengeStore.Streak(1, 1), first)
        val again = store.recordDayCompleted(day) // same day: no double count
        assertEquals(1L, again.current)
        val next = store.recordDayCompleted(day + 1)
        assertEquals(DailyChallengeStore.Streak(2, 2), next)
        val gap = store.recordDayCompleted(day + 5) // gap resets current, best stays
        assertEquals(DailyChallengeStore.Streak(1, 2), gap)
        assertEquals(2L, store.bestStreak())
        assertEquals(day + 5, store.lastCompletedDay())
        assertEquals(day + 5, store.lastCompletedDayFlow.first())
        assertEquals(2L, store.bestStreakFlow.first())
        // currentStreak flow: last day is not today/yesterday relative to real today -> lapsed 0.
        assertEquals(0L, store.currentStreak.first())
    }

    @Test fun dailyChallengeCurrentStreakToday() = runBlocking {
        val store = DailyChallengeStore(ctx, "dcnow${System.nanoTime()}")
        store.recordDayCompleted(store.todayEpochDay())
        assertEquals(1L, store.currentStreak.first())
    }

    private suspend fun await(timeoutMs: Long = 5000, cond: suspend () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(20) }
    }

    @Test fun achievementsUnlockProgressAndForget() = runBlocking {
        val mgr = TestAchievements(ctx, "ach${System.nanoTime()}_")
        assertEquals(2, mgr.achievements.size)
        assertEquals("first_win", mgr.achievements[0].id)
        assertTrue(mgr.achievements[1].isSecret)
        mgr.checkExistingAchievements(); assertTrue(mgr.checked)
        assertNull(mgr.newAchievement.value)

        mgr.onAchievementUnlocked("unknown_id") // no-op
        mgr.onProgressUpdated("unknown_id", 99)
        mgr.onProgressUpdated("first_win", 2) // below target 3
        await { mgr.getAchievementStatuses().first().first { it.achievement.id == "first_win" }.progress == 2 }
        var status = mgr.getAchievementStatuses().first().first { it.achievement.id == "first_win" }
        assertEquals(2, status.progress); assertFalse(status.isUnlocked)

        mgr.onProgressUpdated("first_win", 1) // lower progress does not regress
        delay(150)
        status = mgr.getAchievementStatuses().first().first { it.achievement.id == "first_win" }
        assertEquals(2, status.progress)

        mgr.onProgressUpdated("first_win", 3) // reaches target -> unlocks
        await { mgr.newAchievement.value?.id == "first_win" }
        await { mgr.getAchievementStatuses().first().first { it.achievement.id == "first_win" }.isUnlocked }
        mgr.dismissNotification()
        assertNull(mgr.newAchievement.value)

        mgr.forgetAll()
        await {
            val s = mgr.getAchievementStatuses().first().first { it.achievement.id == "first_win" }
            s.progress == 0 && !s.isUnlocked
        }
    }

    @Test fun achievementsDirectUnlockIsIdempotentNotification() = runBlocking {
        val mgr = TestAchievements(ctx, "ach2${System.nanoTime()}_")
        mgr.onAchievementUnlocked("win_10")
        await { mgr.newAchievement.value?.id == "win_10" }
        mgr.dismissNotification()
        mgr.onAchievementUnlocked("win_10") // already in set: no second notification
        delay(200)
        assertNull(mgr.newAchievement.value)
    }

    @Test fun appMessagesDedupAndActionBypass() = runBlocking {
        val received = java.util.concurrent.CopyOnWriteArrayList<AppMessages.Message>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            AppMessages.messages.collect { received += it }
        }
        try {
            // SharedFlow has no replay: posting before the collector subscribes
            // would drop the message, so warm up until one is observed.
            val warmup = "warmup-${System.nanoTime()}"
            var attempt = 0
            await {
                if (received.isEmpty()) AppMessages.show("$warmup-${attempt++}")
                received.isNotEmpty()
            }
            received.clear()
            val unique = "msg-${System.nanoTime()}"
            AppMessages.show(unique)
            AppMessages.show(unique) // deduped within window
            AppMessages.show(unique, actionLabel = "Retry") // action messages bypass dedup
            AppMessages.show("$unique-other", duration = AppMessages.Duration.Long)
            await { received.size >= 3 }
            assertEquals(unique, received[0].text)
            assertEquals("Retry", received[1].actionLabel)
            assertEquals(AppMessages.Duration.Long, received[2].duration)
            assertEquals(AppMessages.Duration.Short, received[0].duration)
            assertNull(received[0].onAction)
        } finally { job.cancel() }
    }

    @Test fun secureResultReceiverForwards() {
        var got: Pair<Int, Bundle?>? = null
        val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) { code, data -> got = code to data }
        val bundle = Bundle().apply { putString("k", "v") }
        receiver.send(42, bundle)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(42, got?.first)
        assertEquals("v", got?.second?.getString("k"))
    }

    @Test fun achievementDataDefaultsAndEquality() {
        val a = Achievement("id1", "Name", "Desc")
        assertEquals(1, a.targetProgress); assertFalse(a.isSecret)
        val status = AchievementStatus(a, progress = 0, isUnlocked = false)
        assertEquals(a, status.achievement)
        assertEquals(Achievement("id1", "Name", "Desc"), a)
    }
}

@androidx.room3.Database(entities = [ManyManyMatching::class], version = 1, exportSchema = false)
abstract class LibraryState2TestDb : RoomDatabase() {
    abstract fun matchingDao(): MatchingDao
}
