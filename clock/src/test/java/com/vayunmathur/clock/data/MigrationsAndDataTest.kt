package com.vayunmathur.clock.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The schema migrations, run against a recording SQLiteConnection proxy (the
 * real database is SQLCipher-bound on the host, but the migrations are plain
 * SQL strings whose exact text is the contract), plus the entities' data-class
 * semantics.
 */
class MigrationsAndDataTest {

    private class RecordingConnection {
        val sql = mutableListOf<String>()

        private fun proxy(iface: Class<*>): Any {
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "prepare" -> {
                        sql += args[0] as String
                        proxy(SQLiteStatement::class.java)
                    }
                    "step" -> false
                    "close" -> null
                    "toString" -> "recording"
                    "hashCode" -> 1
                    "equals" -> false
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE, java.lang.Long.TYPE -> 0
                        else -> null
                    }
                }
            }
            return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
        }

        val connection: SQLiteConnection = proxy(SQLiteConnection::class.java) as SQLiteConnection
    }

    @Test fun migrationsApplyTheirSqlInOrder() { kotlinx.coroutines.runBlocking {
        assertEquals(2, ClockDatabase.migrations.size)
        val recording = RecordingConnection()
        ClockDatabase.migrations[0].migrate(recording.connection)
        ClockDatabase.migrations[1].migrate(recording.connection)
        assertEquals(
            listOf(
                "ALTER TABLE Alarm ADD COLUMN ringtoneUri TEXT",
                "ALTER TABLE Alarm ADD COLUMN vibrate INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE Alarm ADD COLUMN snoozeMinutes INTEGER NOT NULL DEFAULT 5",
                "ALTER TABLE Alarm ADD COLUMN gradualVolumeSeconds INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE Alarm ADD COLUMN snoozedUntil INTEGER",
            ),
            recording.sql,
        )
    } }

    @Test fun alarmDataClassSemantics() {
        val alarm = Alarm(
            time = LocalTime(7, 30), name = "Gym", enabled = true, days = 0b0100010,
            ringtoneUri = "content://media/1", vibrate = false, snoozeMinutes = 10,
            gradualVolumeSeconds = 30,
            snoozedUntil = Instant.parse("2026-11-16T12:00:00Z"), id = 9,
        )
        assertEquals(alarm, alarm.copy())
        assertEquals(alarm.hashCode(), alarm.copy().hashCode())
        assertNotEquals(alarm, alarm.copy(enabled = false))
        assertNotEquals(alarm, alarm.copy(snoozedUntil = null))
        assertTrue(alarm.toString().contains("Gym"))
    }

    @Test fun timerDataClassSemantics() {
        val now = Clock.System.now()
        val timer = Timer(true, "tea", now, 3.minutes, 5.minutes, id = 4)
        assertEquals(timer, timer.copy())
        assertEquals(timer.hashCode(), timer.copy().hashCode())
        assertNotEquals(timer, timer.copy(isRunning = false))
        assertTrue(timer.toString().contains("tea"))
    }

}
