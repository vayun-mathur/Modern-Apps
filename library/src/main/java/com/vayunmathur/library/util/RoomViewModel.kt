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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Delete
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

class DaoInterface<T: DatabaseItem<T>>(val dao: TrueDao<T>, val viewModelScope: CoroutineScope) {
    val data: StateFlow<List<T>> = dao.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

class DatabaseViewModel(vararg daos: Pair<KClass<*>, TrueDao<*>>) : ViewModel() {
    val daoMap = daos.associate {
        it.first to DaoInterface(it.second, viewModelScope)
    }

    inline fun <reified E : DatabaseItem<E>> getDaoInterface(): DaoInterface<E> {
        val daoInterface = daoMap[E::class] ?: throw Exception("No DAO registered for ${E::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return daoInterface as DaoInterface<E>
    }

    inline fun <reified E: DatabaseItem<E>> data(): StateFlow<List<E>> {
        return getDaoInterface<E>().data
    }

    @Composable
    inline fun <reified E: DatabaseItem<E>> get(id: Long, crossinline default: () -> E? = {null}): State<E> {
        val data by getDaoInterface<E>().data.collectAsState()
        val derived = remember { derivedStateOf { (data.firstOrNull { it.id == id } ?: default())!! } }
        return derived
    }

    inline fun <reified E: DatabaseItem<E>> upsertAll(items: List<E>) {
        getDaoInterface<E>().upsertAll(items)
    }

    @Composable
    inline fun <reified E : DatabaseItem<E>> getEditable(
        initialId: Long,
        crossinline default: () -> E? = { null }
    ): MutableState<E> {
        val daoInterface = getDaoInterface<E>()

        // 1. Track the current ID. If it's 0, it will be updated after the first upsert.
        var currentId by remember { mutableLongStateOf(initialId) }

        // 2. Observe the database state
        val data by daoInterface.data.collectAsState()

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

    inline fun <reified E: DatabaseItem<E>> upsert(t: E, noinline andThen: (Long) -> Unit = {}) {
        getDaoInterface<E>().upsert(t, andThen)
    }

    inline fun <reified E: DatabaseItem<E>> delete(t: E, noinline andThen: (Int) -> Unit = {}) {
        getDaoInterface<E>().delete(t, andThen)
    }
}

abstract class DatabaseItem<T: DatabaseItem<T>> {
    abstract val id: Long
    abstract val position: Double

    fun isNew() = id == 0L
    abstract fun withPosition(position: Double): T
}

interface TrueDao<T: DatabaseItem<T>> {
    fun getAll(): Flow<List<T>>
    @Upsert
    suspend fun upsert(value: T): Long
    @Delete
    suspend fun delete(value: T): Int
    @Upsert
    suspend fun upsertAll(t: List<T>)
}

inline fun <reified T: RoomDatabase> Context.buildDatabase(migrations: List<Migration> = emptyList()): T {
    return Room.databaseBuilder(
        this,
        T::class.java,
        "passwords-db"
    ).addMigrations(*migrations.toTypedArray()).build()
}