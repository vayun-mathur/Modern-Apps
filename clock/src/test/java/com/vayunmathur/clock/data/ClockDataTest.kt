package com.vayunmathur.clock.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.runner.RunWith
import com.vayunmathur.clock.FakeAndroidKeyStore
import org.junit.Before
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock

/** Tests for the Room DAOs and the [Timer]/[Alarm] entity helpers. */
@RunWith(RobolectricTestRunner::class)
class ClockDataTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()


    private fun db(): ClockDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder<ClockDatabase>(ctx).allowMainThreadQueries().build()
    }

    private fun timer(name: String = "t", running: Boolean = false) = Timer(
        isRunning = running, name = name,
        remainingStartTime = Clock.System.now(),
        remainingLength = 5.minutes, totalLength = 5.minutes,
    )

    private fun alarm(hour: Int = 7, name: String = "a") = Alarm(
        time = LocalTime(hour, 30), name = name, enabled = true, days = 0,
    )

    @Test
    fun timerDaoCrudAndFlow() = runBlocking {
        val db = db()
        val id = db.timerDao().upsert(timer("one"))
        assertTrue(id > 0)
        db.timerDao().upsert(timer("two"))
        assertEquals(2, db.timerDao().getAll().size)
        assertEquals("one", db.timerDao().get(id).name)
        assertEquals(2, db.timerDao().getAllFlow().first().size)
        // Upsert with an existing id updates in place.
        val existing = db.timerDao().get(id)
        db.timerDao().upsert(existing.copy(name = "renamed"))
        assertEquals("renamed", db.timerDao().get(id).name)
        assertEquals(1, db.timerDao().delete(db.timerDao().get(id)))
        assertEquals(1, db.timerDao().getAll().size)
        db.close()
    }

    @Test
    fun alarmDaoOrdersByTime() = runBlocking {
        val db = db()
        db.alarmDao().upsert(alarm(9, "late"))
        db.alarmDao().upsert(alarm(6, "early"))
        val all = db.alarmDao().getAll()
        assertEquals(listOf("early", "late"), all.map { it.name })
        assertEquals(2, db.alarmDao().getAllFlow().first().size)
        assertEquals(1, db.alarmDao().delete(all[0]))
        db.close()
    }

    @Test
    fun timerStoppedAndStarted() {
        val running = timer(running = true)
        val stopped = running.stopped()
        assertFalse(stopped.isRunning)
        assertTrue(stopped.remainingLength <= 5.minutes)
        val started = stopped.started()
        assertTrue(started.isRunning)
    }

    @Test
    fun timerStoppedAfterExpiryClampsToZero() {
        val overdue = Timer(
            isRunning = true, name = "overdue",
            remainingStartTime = Clock.System.now() - 10.minutes,
            remainingLength = 5.minutes, totalLength = 5.minutes,
        )
        val stopped = overdue.stopped()
        assertFalse(stopped.isRunning)
        assertEquals(kotlin.time.Duration.ZERO, stopped.remainingLength)
    }

    @Test
    fun alarmDefaults() {
        val a = alarm()
        assertEquals(null, a.ringtoneUri)
        assertTrue(a.vibrate)
        assertEquals(5, a.snoozeMinutes)
        assertEquals(0, a.gradualVolumeSeconds)
        assertEquals(null, a.snoozedUntil)
    }

    @Test
    fun migrationsDeclared() {
        assertEquals(2, ClockDatabase.migrations.size)
        assertEquals(listOf(1 to 2, 2 to 3), ClockDatabase.migrations.map { it.startVersion to it.endVersion })
    }

}
