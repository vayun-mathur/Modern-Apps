package com.vayunmathur.clock.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for all Clock persisted data.
 *
 * Owns the one [ClockDatabase] instance (via [RoomRepository]) and is the only place
 * the two DAOs are touched. All consumers — MainActivity/ViewModel, intents,
 * receivers, services — go through here.
 *
 * Built with `useDeviceProtectedStorage = true` so alarms remain available
 * before the user unlocks the device after a reboot.
 */
class ClockRepository private constructor(context: Context) :
    RoomRepository<ClockDatabase>(context, ClockDatabase::class, useDeviceProtectedStorage = true) {

    private val timerDao: TimerDao get() = db.timerDao()
    private val alarmDao: AlarmDao get() = db.alarmDao()

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val timers: Flow<List<Timer>> get() = timerDao.getAllFlow()
    val alarms: Flow<List<Alarm>> get() = alarmDao.getAllFlow()

    // ------------------------------------------------------------------
    // Timer reads / writes
    // ------------------------------------------------------------------

    suspend fun getTimer(id: Long): Timer = timerDao.get(id)
    suspend fun getAllTimers(): List<Timer> = timerDao.getAll()
    suspend fun upsertTimer(timer: Timer): Long = timerDao.upsert(timer)
    suspend fun deleteTimer(timer: Timer): Int = timerDao.delete(timer)

    // ------------------------------------------------------------------
    // Alarm reads / writes
    // ------------------------------------------------------------------

    suspend fun getAlarm(id: Long): Alarm = alarmDao.get(id)
    suspend fun getAllAlarms(): List<Alarm> = alarmDao.getAll()
    suspend fun upsertAlarm(alarm: Alarm): Long = alarmDao.upsert(alarm)
    suspend fun deleteAlarm(alarm: Alarm): Int = alarmDao.delete(alarm)

    /** Holds the process-wide [ClockRepository] singleton. */
    companion object {
        @Volatile
        private var instance: ClockRepository? = null

        /** The process-wide singleton repository. */
        fun get(context: Context): ClockRepository =
            instance ?: synchronized(this) {
                instance ?: ClockRepository(context).also { instance = it }
            }
    }
}
