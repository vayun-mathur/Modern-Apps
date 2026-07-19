package com.vayunmathur.library.util

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant


interface DatabaseItem {
    val id: Long
}

@Entity
data class ManyManyMatching(
    val leftID: Long,
    val rightID: Long,
    val type: Int,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

interface ReorderableDatabaseItem<T: ReorderableDatabaseItem<T>>: DatabaseItem {
    val position: Double
    fun withPosition(position: Double): T
}

@Dao
interface MatchingDao {
    @Upsert
    suspend fun upsert(value: ManyManyMatching): Long
    @Upsert
    suspend fun upsert(value: List<ManyManyMatching>)
    @Delete
    suspend fun delete(value: ManyManyMatching): Int

    @Query("SELECT rightID FROM ManyManyMatching WHERE leftID = :leftID AND type = :type")
    suspend fun getFromLeft(leftID: Long, type: Int): List<Long>
    @Query("SELECT leftID FROM ManyManyMatching WHERE rightID = :rightID AND type = :type")
    suspend fun getFromRight(rightID: Long, type: Int): List<Long>
    @Query("DELETE FROM ManyManyMatching WHERE leftID = :leftID AND type = :type")
    suspend fun deleteFromLeft(leftID: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE rightID = :rightID AND type = :type")
    suspend fun deleteFromRight(rightID: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE leftID = :left AND rightID = :right AND type = :type")
    suspend fun deleteMatch(left: Long, right: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE type = :type")
    suspend fun deleteByType(type: Int)

    @Query("DELETE FROM ManyManyMatching")
    suspend fun clear()
    @Query("SELECT * FROM ManyManyMatching")
    fun flow(): Flow<List<ManyManyMatching>>
}

val databases: MutableMap<KClass<*>, RoomDatabase> = mutableMapOf()

inline fun <reified T : RoomDatabase> closeCachedDatabase() {
    synchronized(databases) {
        val db = databases.remove(T::class)
        // Closing can race with in-flight queries; ignore the resulting failure
        // since we are discarding the instance anyway.
        try { db?.close() } catch (_: RuntimeException) {}
    }
}

/**
 * Implemented by a [RoomDatabase] companion object to declare the migrations
 * for that database in one place — alongside the schema definition itself
 * rather than scattered across every `buildDatabase()` call site.
 *
 * Example:
 * ```
 * abstract class NotesDatabase : RoomDatabase() {
 *     abstract fun notesDao(): NotesDao
 *     companion object : DatabaseMigrations {
 *         override val migrations = listOf(MIGRATION_1_2, MIGRATION_2_3)
 *     }
 * }
 * ```
 */
interface DatabaseMigrations {
    val migrations: List<Migration>
}

class DefaultConverters {
    @TypeConverter
    fun fromInstant(value: Instant) = value.epochSeconds
    @TypeConverter
    fun toInstant(value: Long) = Instant.fromEpochSeconds(value)
    @TypeConverter
    fun fromList(value: List<Long>?): String? {
        return value?.let { Json.encodeToString(it) }
    }
    @TypeConverter
    fun toList(value: String?): List<Long>? {
        return value?.let { Json.decodeFromString<List<Long>>(it) }
    }
    @TypeConverter
    fun fromListS(value: List<String>): String {
        return Json.encodeToString(value)
    }
    @TypeConverter
    fun toListS(value: String): List<String> {
        return Json.decodeFromString<List<String>>(value)
    }

    @TypeConverter
    fun fromDuration(value: Duration) = value.inWholeMilliseconds
    @TypeConverter
    fun toDuration(value: Long) = value.milliseconds

    @TypeConverter
    fun fromLocalTime(value: LocalTime) = value.toSecondOfDay()
    @TypeConverter
    fun toLocalTime(value: Int) = LocalTime.fromSecondOfDay(value)
}
