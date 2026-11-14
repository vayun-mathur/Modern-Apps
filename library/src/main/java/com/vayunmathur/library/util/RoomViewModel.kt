package com.vayunmathur.library.util

import androidx.room3.ColumnTypeConverter
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant


/** A Room entity identified by a numeric primary key. */
interface DatabaseItem {
    val id: Long
}

/** Join row linking a left id to a right id under a typed many-to-many relation. */
@Entity
data class ManyManyMatching(
    val leftID: Long,
    val rightID: Long,
    val type: Int,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

/** A [DatabaseItem] with a sortable position that can be rewritten on reorder. */
interface ReorderableDatabaseItem<T: ReorderableDatabaseItem<T>>: DatabaseItem {
    val position: Double
    fun withPosition(position: Double): T
}

/** DAO over [ManyManyMatching] join rows: upsert, delete and directional lookups. */
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

/** Room type converters for instants, durations and JSON-encoded lists. */
class DefaultConverters {
    @ColumnTypeConverter
    fun fromInstant(value: Instant) = value.epochSeconds
    @ColumnTypeConverter
    fun toInstant(value: Long) = Instant.fromEpochSeconds(value)
    @ColumnTypeConverter
    fun fromInstantNullable(value: Instant?): Long? = value?.epochSeconds
    @ColumnTypeConverter
    fun toInstantNullable(value: Long?): Instant? = value?.let { Instant.fromEpochSeconds(it) }

    @ColumnTypeConverter
    fun fromList(value: List<Long>?): String? {
        return value?.let { Json.encodeToString(it) }
    }
    @ColumnTypeConverter
    fun toList(value: String?): List<Long>? {
        return value?.let { Json.decodeFromString<List<Long>>(it) }
    }
    @ColumnTypeConverter
    fun fromListS(value: List<String>): String {
        return Json.encodeToString(value)
    }
    @ColumnTypeConverter
    fun toListS(value: String): List<String> {
        return Json.decodeFromString<List<String>>(value)
    }

    @ColumnTypeConverter
    fun fromDuration(value: Duration) = value.inWholeMilliseconds
    @ColumnTypeConverter
    fun toDuration(value: Long) = value.milliseconds
    @ColumnTypeConverter
    fun fromDurationNullable(value: Duration?): Long? = value?.inWholeMilliseconds
    @ColumnTypeConverter
    fun toDurationNullable(value: Long?): Duration? = value?.milliseconds

    @ColumnTypeConverter
    fun fromLocalTime(value: LocalTime) = value.toSecondOfDay()
    @ColumnTypeConverter
    fun toLocalTime(value: Int) = LocalTime.fromSecondOfDay(value)
}
