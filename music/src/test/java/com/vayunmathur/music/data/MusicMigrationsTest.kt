package com.vayunmathur.music.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MusicDatabase's migrations and matching type codes, driven against a
 * recording SQLiteConnection proxy: creating a real database needs SQLCipher
 * (unavailable on a host JVM), but a migration is just the SQL it prepares, in
 * order — and issuing the wrong SQL is exactly what a migration test catches.
 * Room 3 migrations are suspending and take an androidx.sqlite connection,
 * whose execSQL goes through prepare/step, so the proxy records prepares.
 */
class MusicMigrationsTest {

    private class RecordingDb {
        val sql = mutableListOf<String>()

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            String::class.java -> ""
            else -> null
        }

        private val statement: SQLiteStatement = Proxy.newProxyInstance(
            SQLiteStatement::class.java.classLoader,
            arrayOf(SQLiteStatement::class.java),
            InvocationHandler { _, method, _ -> defaultValue(method.returnType) },
        ) as SQLiteStatement

        val connection: SQLiteConnection = Proxy.newProxyInstance(
            SQLiteConnection::class.java.classLoader,
            arrayOf(SQLiteConnection::class.java),
            InvocationHandler { _, method, args ->
                if (method.name == "prepare" && args?.firstOrNull() is String) {
                    sql += args.first() as String
                    statement
                } else {
                    defaultValue(method.returnType)
                }
            },
        ) as SQLiteConnection
    }

    @Test
    fun matchingTypeCodesFollowTheMinPlusHundredMaxScheme() {
        assertEquals(300, TYPE_MUSIC_PLAYLIST)
        assertEquals(201, TYPE_ALBUM_ARTIST)
    }

    @Test
    fun migration1To2CreatesThePlaylistTable() {
        val recording = RecordingDb()
        runBlocking { MIGRATION_1_2.migrate(recording.connection) }
        assertEquals(1, recording.sql.size)
        assertTrue(recording.sql.single().contains("CREATE TABLE IF NOT EXISTS `Playlist`"))
        assertTrue(recording.sql.single().contains("`name` TEXT NOT NULL"))
    }

    @Test
    fun migration2To3AddsDurationTrackAndYearColumnsInOrder() {
        val recording = RecordingDb()
        runBlocking { MIGRATION_2_3.migrate(recording.connection) }
        assertEquals(
            listOf(
                "ALTER TABLE Music ADD COLUMN duration INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE Music ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE Music ADD COLUMN year INTEGER NOT NULL DEFAULT 0",
            ),
            recording.sql,
        )
    }

    @Test
    fun migration3To4AddsTheDiscNumberColumn() {
        val recording = RecordingDb()
        runBlocking { MIGRATION_3_4.migrate(recording.connection) }
        assertEquals(
            listOf("ALTER TABLE Music ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 1"),
            recording.sql,
        )
    }

    @Test
    fun migration4To5DropsAlbumArtistTablesButKeepsPlaylistMatchings() {
        val recording = RecordingDb()
        runBlocking { MIGRATION_4_5.migrate(recording.connection) }
        assertEquals(
            listOf(
                "DROP TABLE IF EXISTS Album",
                "DROP TABLE IF EXISTS Artist",
                "DELETE FROM ManyManyMatching WHERE type = $TYPE_ALBUM_ARTIST",
            ),
            recording.sql,
        )
        assertTrue(recording.sql.none { it.contains(TYPE_MUSIC_PLAYLIST.toString()) })
    }

    @Test
    fun theRegisteredMigrationChainRuns1To6WithoutGaps() {
        assertEquals(
            listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6),
            MusicDatabase.migrations.map { it.startVersion to it.endVersion },
        )
        val recording = RecordingDb()
        runBlocking { MusicDatabase.migrations.forEach { it.migrate(recording.connection) } }
        assertEquals(1 + 3 + 1 + 3 + 2, recording.sql.size)
    }
}
