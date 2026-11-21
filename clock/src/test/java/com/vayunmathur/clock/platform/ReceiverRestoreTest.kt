package com.vayunmathur.clock.platform

import android.content.Context
import android.content.Intent
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.library.util.databases
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.vayunmathur.testsupport.awaitWithMainIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The boot and timer-action receivers against a real repository: earlier
 * receiver suites let the database open fail on the host and only pinned
 * fail-soft dispatch, so BootReceiver's restore pass and
 * TimerActionReceiver's pause/resume/cancel/reset bodies never ran.
 * Seeding the shared database cache with an in-memory database (the
 * NotesRepository test pattern) makes ClockRepository fully functional.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverRestoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val repo: ClockRepository get() = ClockRepository.get(ctx)

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        // Other receiver/ViewModel suites in this Robolectric sandbox may
        // already have bound ClockRepository (and the shared database cache)
        // to the on-disk SQLCipher database, which a host JVM cannot open.
        // Reset both so the repository binds to the in-memory database below.
        runCatching {
            val field = ClockRepository::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
        synchronized(databases) {
            runCatching { databases[ClockDatabase::class]?.close() }
            databases[ClockDatabase::class] =
                Room.inMemoryDatabaseBuilder<ClockDatabase>(ctx.applicationContext)
                    .allowMainThreadQueries().build()
        }
        createNotificationChannels(ctx)
        runBlocking {
            repo.getAllTimers().forEach { repo.deleteTimer(it) }
            repo.getAllAlarms().forEach { repo.deleteAlarm(it) }
        }
    }

    @After fun tearDown() {
        // Hand the sandbox back the way earlier suites left it: no bound
        // repository and no cached in-memory database (see setUp).
        runCatching {
            val field = ClockRepository::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
        synchronized(databases) {
            runCatching { databases[ClockDatabase::class]?.close() }
            databases.remove(ClockDatabase::class)
        }
    }


    private fun timer(name: String, running: Boolean, remaining: Duration, startedAgo: Duration = 0.seconds) =
        Timer(
            isRunning = running, name = name,
            remainingStartTime = Clock.System.now() - startedAgo,
            remainingLength = remaining, totalLength = 5.minutes,
        )

    @Test fun bootRearmsFutureTimersAndCompletesExpiredOnes() {
        val futureId = runBlocking { repo.upsertTimer(timer("future", true, 5.minutes)) }
        val expiredId = runBlocking {
            repo.upsertTimer(timer("expired", true, 1.seconds, startedAgo = 10.minutes))
        }
        val pausedId = runBlocking { repo.upsertTimer(timer("paused", false, 2.minutes)) }

        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))

        awaitWithMainIdle("expired timer completed on boot") { runCatching { val expired = runBlocking { repo.getTimer(expiredId) }; !expired.isRunning && expired.remainingLength == Duration.ZERO }.getOrDefault(false) }
        val future = runBlocking { repo.getTimer(futureId) }
        assertTrue(future.isRunning, "a timer still in the future must stay running after boot")
        val paused = runBlocking { repo.getTimer(pausedId) }
        assertFalse(paused.isRunning)
        assertEquals(2.minutes, paused.remainingLength, "paused timers are not the boot receiver's business")
    }

    @Test fun bootClearsSpentSnoozesAndKeepsFutureOnes() {
        val now = Clock.System.now()
        val spentId = runBlocking {
            repo.upsertAlarm(Alarm(LocalTime(7, 0), "spent", true, 0, snoozedUntil = now - 5.minutes))
        }
        val futureId = runBlocking {
            repo.upsertAlarm(Alarm(LocalTime(7, 5), "future", true, 0, snoozedUntil = now + 5.minutes))
        }
        runBlocking { repo.upsertAlarm(Alarm(LocalTime(8, 0), "off", false, 0, snoozedUntil = now - 5.minutes)) }

        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_TIME_CHANGED))

        awaitWithMainIdle("spent snooze cleared") { runCatching { runBlocking { repo.getAlarm(spentId) }.snoozedUntil == null }.getOrDefault(false) }
        // (Persisted instants keep second precision, so compare by range.)
        val kept = runBlocking { repo.getAlarm(futureId) }.snoozedUntil
        assertTrue(
            kept != null && kept > now,
            "a snooze still in the future must be re-armed, not cleared, was $kept",
        )
    }

    @Test fun timerActionsPauseResumeResetAndCancelPersist() {
        val id = runBlocking { repo.upsertTimer(timer("actions", true, 5.minutes, startedAgo = 1.minutes)) }
        fun fire(action: String) =
            TimerActionReceiver().onReceive(ctx, Intent(action).apply { putExtra("timer_id", id) })

        fire(TimerActionReceiver.ACTION_PAUSE)
        awaitWithMainIdle("pause persisted") { runCatching { !runBlocking { repo.getTimer(id) }.isRunning }.getOrDefault(false) }
        val paused = runBlocking { repo.getTimer(id) }
        assertTrue(
            paused.remainingLength in 3.minutes..5.minutes,
            "pause must bank roughly four remaining minutes, was ${paused.remainingLength}",
        )

        fire(TimerActionReceiver.ACTION_RESUME)
        awaitWithMainIdle("resume persisted") { runCatching { runBlocking { repo.getTimer(id) }.isRunning }.getOrDefault(false) }

        fire(TimerActionReceiver.ACTION_RESET)
        awaitWithMainIdle("reset persisted") { runCatching { val t = runBlocking { repo.getTimer(id) }; !t.isRunning && t.remainingLength == t.totalLength }.getOrDefault(false) }

        fire(TimerActionReceiver.ACTION_CANCEL)
        awaitWithMainIdle("cancel persisted") { runCatching { runBlocking { repo.getAllTimers() }.none { it.id == id } }.getOrDefault(false) }
    }

    @Test fun timerActionOnPausedTimerKeepsItsBankedRemaining() {
        val id = runBlocking { repo.upsertTimer(timer("banked", false, 90.seconds)) }
        TimerActionReceiver().onReceive(
            ctx,
            Intent(TimerActionReceiver.ACTION_PAUSE).apply { putExtra("timer_id", id) },
        )
        awaitWithMainIdle("banked pause settled") { runCatching { runBlocking { repo.getTimer(id) }.remainingLength == 90.seconds }.getOrDefault(false) }
        assertFalse(runBlocking { repo.getTimer(id) }.isRunning)
    }
}
