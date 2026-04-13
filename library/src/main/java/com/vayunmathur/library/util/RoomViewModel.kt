package com.vayunmathur.library.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.InvalidationTracker
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant


class DatabaseViewModel(val database: RoomDatabase, vararg daos: Pair<KClass<*>, TrueDao<*>>, val matchingDao: MatchingDao? = null) : ViewModel() {
    val daos = daos.associate { it.first to it.second }

    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> addPairs(pairs: List<Pair<A, B>>) {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val pairs = if(classAIndex < classBIndex) pairs else pairs.map { it.second to it.first }
        viewModelScope.launch {
            matchingDao!!.upsert(pairs.map { (a, b) -> ManyManyMatching(a.id, b.id, type) })
        }
    }

    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> clearMatchings() {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        viewModelScope.launch {
            matchingDao!!.deleteByType(type)
        }
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> getMatches(a: Long): List<Long> {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val ids = if(classAIndex < classBIndex) matchingDao!!.getFromLeft(a, type) else matchingDao!!.getFromRight(a, type)
        return ids
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> match(idA: Long, idB: Long) {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val match = if(classAIndex < classBIndex) ManyManyMatching(idA, idB, type) else ManyManyMatching(idB, idA, type)
        matchingDao!!.upsert(match)
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> unmatch(idA: Long, idB: Long) {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        if(classAIndex < classBIndex) matchingDao!!.deleteMatch(idA, idB, type) else matchingDao!!.deleteMatch(idB, idA, type)
    }

    val matchesStateFlow = matchingDao?.flow()?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Composable
    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> getMatchesState(a: Long): State<List<Long>> {
        val classAIndex = daos.keys.indexOf(A::class)
        val classBIndex = daos.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)

        val matches by matchesStateFlow!!.collectAsState()
        return remember { derivedStateOf {
            if(classAIndex < classBIndex) matches.filter { it.leftID == a && it.type == type }.map { it.rightID } else matches.filter { it.rightID == a && it.type == type }.map { it.leftID }
        } }
    }


    inline fun <reified E : DatabaseItem> getDao(): TrueDao<E> {
        val dao = daos[E::class] ?: throw Exception("No DAO registered for ${E::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return dao as TrueDao<E>
    }

    @Composable
    inline fun <reified E: DatabaseItem> getState(id: Long, crossinline default: () -> E? = {null}): State<E> {
        val data by data<E>().collectAsState()
        val derived = remember { derivedStateOf { (data.firstOrNull { it.id == id } ?: default())!! } }
        return derived
    }

    @Composable
    inline fun <reified E: DatabaseItem> getNullable(id: Long): State<E?> {
        val data by data<E>().collectAsState()
        val derived = remember { derivedStateOf { (data.firstOrNull { it.id == id }) } }
        return derived
    }

    @Composable
    inline fun <reified E : DatabaseItem> getEditable(
        initialId: Long,
        crossinline default: () -> E? = { null }
    ): MutableState<E> {
        // 1. Track the current ID. If it's 0, it will be updated after the first upsert.
        var currentId by remember { mutableLongStateOf(initialId) }

        // 2. Observe the database state
        val data by data<E>().collectAsState(listOf())

        // 3. Local state for immediate UI feedback
        val localState = remember { mutableStateOf<E?>(null) }

        // Sync local state when the database data changes or the ID changes
        LaunchedEffect(data, currentId) {
            val dbItem = data.firstOrNull { it.id == currentId }
            if (dbItem != null) {
                localState.value = dbItem
            }
        }

        // 4. Wrap in a custom MutableState
        return remember {
            object : MutableState<E> {
                override var value: E
                    get() = localState.value ?: default() ?: throw Exception("Entity not found and no default provided")
                    set(newValue) {
                        // Optimistically update the UI local state
                        localState.value = newValue

                        // Push to database
                        upsertAsync(newValue) { newId ->
                            // If this was a new item (ID 0), update our pointer to the new ID
                            if (currentId == 0L) {
                                currentId = newId
                            }
                        }
                    }

                override fun component1(): E = value
                override fun component2(): (E) -> Unit = { value = it }
            }
        }
    }

    val dataStateCache = mutableMapOf<Pair<KClass<*>, String?>, StateFlow<List<*>>>()

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : DatabaseItem> data(filterQuery: String? = null): StateFlow<List<E>> {
        return dataStateCache.getOrPut(Pair(E::class, filterQuery)) {
            val tableName = E::class.simpleName!!

            callbackFlow<List<E>> {
                // Fetch initial data
                send(getAll<E>(filterQuery))

                // 1. Create an observer for the specific table name
                val observer = object : InvalidationTracker.Observer(tableName) {
                    override fun onInvalidated(tables: Set<String>) {
                        // When the table changes, re-fetch the data
                        launch { send(getAll<E>(filterQuery)) }
                    }
                }

                database.invalidationTracker.addObserver(observer)

                // 3. Clean up the observer when the UI stops listening
                awaitClose { database.invalidationTracker.removeObserver(observer) }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        } as StateFlow<List<E>>
    }

    inline fun <reified E: DatabaseItem> upsertAll(items: List<E>) {
        viewModelScope.launch {
            getDao<E>().upsertAll(items)
        }
    }

    inline fun <reified E: DatabaseItem> replaceAll(items: List<E>) {
        database.openHelper.writableDatabase.delete(E::class.simpleName!!, null, null)
        upsertAll(items)
    }

    suspend inline fun <reified E: DatabaseItem> getAll(filterQuery: String? = null): List<E> {
        return getDao<E>().getAll<E>(filterQuery)
    }

    suspend inline fun <reified E: DatabaseItem> get(id: Long): E {
        return getDao<E>().get<E>(id)
    }

    inline fun <reified E: DatabaseItem> upsertAsync(t: E, noinline andThen: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = getDao<E>().upsert(t)
            andThen(id)
        }
    }

    suspend inline fun <reified E: DatabaseItem> upsert(t: E): Long {
        return getDao<E>().upsert(t)
    }

    inline fun <reified E: DatabaseItem> delete(t: E) {
        viewModelScope.launch {
            getDao<E>().delete(t)
        }
    }

    inline fun <reified E: DatabaseItem> deleteIf(filter: String) {
        viewModelScope.launch {
            getDao<E>().observeNothing(SimpleSQLiteQuery("DELETE FROM ${E::class.simpleName} WHERE $filter"))
        }
    }

    inline fun <reified E: DatabaseItem> update(id: Long, crossinline function: (E) -> E) {
        viewModelScope.launch {
            val t = getDao<E>().get<E>(id)
            getDao<E>().upsert(function(t))
        }
    }
}

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

fun DatabaseItem.isNew() = id == 0L

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
    @Query("DELETE FROM ManyManyMatching WHERE leftID = :left AND rightID = :right AND type = :type")
    suspend fun deleteMatch(left: Long, right: Long, type: Int)
    @Query("DELETE FROM ManyManyMatching WHERE type = :type")
    suspend fun deleteByType(type: Int)

    @Query("DELETE FROM ManyManyMatching")
    suspend fun clear()
    @Query("SELECT * FROM ManyManyMatching")
    fun flow(): Flow<List<ManyManyMatching>>
}

interface TrueDao<T: DatabaseItem> {
    @Upsert
    suspend fun upsert(value: T): Long
    @Delete
    suspend fun delete(value: T): Int
    @Upsert
    suspend fun upsertAll(t: List<T>)
    @RawQuery
    suspend fun observeRawList(query: SupportSQLiteQuery): List<T>
    @RawQuery
    suspend fun observeRaw(query: SupportSQLiteQuery): T
    @RawQuery
    suspend fun observeNothing(query: SupportSQLiteQuery): Long
    @RawQuery
    suspend fun observeRawNullable(query: SupportSQLiteQuery): T?
}

suspend inline fun <reified E : DatabaseItem> TrueDao<E>.getAll(filterQuery: String? = null): List<E> {
    val tableName = E::class.simpleName!!
    if(filterQuery == null) {
        return observeRawList(SimpleSQLiteQuery("SELECT * FROM $tableName"))
    }
    return observeRawList(SimpleSQLiteQuery("SELECT * FROM $tableName WHERE $filterQuery"))
}

suspend inline fun <reified E : DatabaseItem> TrueDao<E>.get(id: Long): E {
    val tableName = E::class.simpleName!!
    return observeRaw(SimpleSQLiteQuery("SELECT * FROM $tableName WHERE id = $id"))
}

suspend inline fun <reified E : DatabaseItem> TrueDao<E>.getNullable(id: Long): E? {
    val tableName = E::class.simpleName!!
    return observeRawNullable(SimpleSQLiteQuery("SELECT * FROM $tableName WHERE id = $id"))
}

val databases: MutableMap<KClass<*>, RoomDatabase> = mutableMapOf()

inline fun <reified T: RoomDatabase> Context.buildDatabase(migrations: List<Migration> = emptyList()): T {
    if(databases[T::class] == null) {
        databases[T::class] = Room.databaseBuilder(
            this,
            T::class.java,
            "passwords-db"
        ).addMigrations(*migrations.toTypedArray()).build()
    }
    return databases[T::class]!! as T
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