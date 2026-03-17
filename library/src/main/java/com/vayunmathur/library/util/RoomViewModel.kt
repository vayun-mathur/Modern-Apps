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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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

class DaoInterface<T: DatabaseItem>(val dao: TrueDao<T>, val viewModelScope: CoroutineScope) {
    fun delete(t: T, andThen: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val id = dao.delete(t)
            andThen(id)
        }
    }

    fun upsert(t: T, andThen: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = dao.upsert(t)
            andThen(id)
        }
    }

    fun upsertAll(t: List<T>) {
        viewModelScope.launch {
            dao.upsertAll(t)
        }
    }
}

class DatabaseViewModel(val database: RoomDatabase, vararg daos: Pair<KClass<*>, TrueDao<*>>, val matchingDao: MatchingDao? = null) : ViewModel() {
    val daoMap = daos.associate {
        it.first to DaoInterface(it.second, viewModelScope)
    }

    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> addPairs(pairs: List<Pair<A, B>>) {
        val classAIndex = daoMap.keys.indexOf(A::class)
        val classBIndex = daoMap.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val pairs = if(classAIndex < classBIndex) pairs else pairs.map { it.second to it.first }
        viewModelScope.launch {
            matchingDao!!.upsert(pairs.map { (a, b) -> ManyManyMatching(a.id, b.id, type) })
        }
    }

    fun clearMatchings() {
        viewModelScope.launch {
            matchingDao!!.clear()
        }
    }

    suspend inline fun <reified A: DatabaseItem, reified B: DatabaseItem> getMatches(a: Long): List<Long> {
        val classAIndex = daoMap.keys.indexOf(A::class)
        val classBIndex = daoMap.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)
        val ids = if(classAIndex < classBIndex) matchingDao!!.getFromLeft(a, type) else matchingDao!!.getFromRight(a, type)
        return ids
    }

    val matchesStateFlow = matchingDao?.flow()?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @Composable
    inline fun <reified A: DatabaseItem, reified B: DatabaseItem> getMatchesState(a: Long): State<List<Long>> {
        val classAIndex = daoMap.keys.indexOf(A::class)
        val classBIndex = daoMap.keys.indexOf(B::class)
        val type = min(classAIndex, classBIndex) + 100 * max(classAIndex, classBIndex)

        val matches by matchesStateFlow!!.collectAsState()
        return remember { derivedStateOf {
            if(classAIndex < classBIndex) matches.filter { it.leftID == a && it.type == type }.map { it.rightID } else matches.filter { it.rightID == a && it.type == type }.map { it.leftID }
        } }
    }


    inline fun <reified E : DatabaseItem> getDaoInterface(): DaoInterface<E> {
        val daoInterface = daoMap[E::class] ?: throw Exception("No DAO registered for ${E::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return daoInterface as DaoInterface<E>
    }

    @Composable
    inline fun <reified E: DatabaseItem> get(id: Long, crossinline default: () -> E? = {null}): State<E> {
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

    inline fun <reified E: DatabaseItem> upsertAll(items: List<E>) {
        getDaoInterface<E>().upsertAll(items)
    }

    inline fun <reified E: DatabaseItem> replaceAll(items: List<E>) {
        database.openHelper.writableDatabase.delete(E::class.simpleName!!, null, null)
        upsertAll(items)
    }

    @Composable
    inline fun <reified E : DatabaseItem> getEditable(
        initialId: Long,
        crossinline default: () -> E? = { null }
    ): MutableState<E> {
        val daoInterface = getDaoInterface<E>()

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
                        daoInterface.upsert(newValue) { newId ->
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

    val dataStateCache = mutableMapOf<KClass<*>, StateFlow<List<*>>>()

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : DatabaseItem> data(): StateFlow<List<E>> {
        return runBlocking {
            dataStateCache.getOrPut(E::class) {
                val tableName = E::class.simpleName!!

                callbackFlow<List<E>> {
                    // 1. Create an observer for the specific table name
                    val observer = object : InvalidationTracker.Observer(tableName) {
                        override fun onInvalidated(tables: Set<String>) {
                            // When the table changes, re-fetch the data
                            launch { send(getAll<E>()) }
                        }
                    }

                    database.invalidationTracker.addObserver(observer)

                    // 3. Clean up the observer when the UI stops listening
                    awaitClose { database.invalidationTracker.removeObserver(observer) }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, getAll())
            } as StateFlow<List<E>>
        }
    }

    suspend inline fun <reified E : DatabaseItem> getAll(): List<E> {
        val tableName = E::class.simpleName!!
        return getDaoInterface<E>().dao.observeRaw(SimpleSQLiteQuery("SELECT * FROM $tableName"))
    }

    inline fun <reified E: DatabaseItem> upsert(t: E, noinline andThen: (Long) -> Unit = {}) {
        getDaoInterface<E>().upsert(t, andThen)
    }

    inline fun <reified E: DatabaseItem> delete(t: E, noinline andThen: (Int) -> Unit = {}) {
        getDaoInterface<E>().delete(t, andThen)
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
    suspend fun observeRaw(query: SupportSQLiteQuery): List<T>
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