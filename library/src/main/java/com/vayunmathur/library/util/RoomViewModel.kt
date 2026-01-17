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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.to

class DaoInterface<T: DatabaseItem>(val dao: TrueDao<T>, val viewModelScope: CoroutineScope) {
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
}

class DatabaseViewModel(vararg daos: Pair<KClass<*>, TrueDao<*>>) : ViewModel() {
    val daoMap = daos.associate {
        it.first to DaoInterface(it.second, viewModelScope)
    }

    inline fun <reified E : DatabaseItem> getDaoInterface(): DaoInterface<E> {
        val daoInterface = daoMap[E::class] ?: throw Exception("No DAO registered for ${E::class.simpleName}")
        @Suppress("UNCHECKED_CAST")
        return daoInterface as DaoInterface<E>
    }

    inline fun <reified E : DatabaseItem> getDao(): TrueDao<E> {
        return getDaoInterface<E>().dao
    }

    inline fun <reified E: DatabaseItem> data(): StateFlow<List<E>> {
        return getDaoInterface<E>().data
    }

    @Composable
    inline fun <reified E: DatabaseItem> get(id: Long, crossinline default: () -> E? = {null}): State<E> {
        val data by getDaoInterface<E>().data.collectAsState()
        val derived = remember { derivedStateOf { (data.firstOrNull { it.id == id } ?: default())!! } }
        return derived
    }

    @Composable
    inline fun <reified E : DatabaseItem> getEditable(
        id: Long,
        crossinline default: () -> E? = { null }
    ): MutableState<E> {
        val scope = rememberCoroutineScope()
        val dao = getDaoInterface<E>()

        // 1. Get the source of truth from the DB
        val data by dao.data.collectAsState(initial = emptyList())

        // 2. Create a local state to hold the "in-progress" edits
        // We initialize it when the data first loads
        val localState = remember { mutableStateOf<E?>(null) }

        var id by remember { mutableLongStateOf(id) }

        // Update local state when the DB record changes
        LaunchedEffect(data) {
            if (localState.value == null) {
                localState.value = data.firstOrNull { it.id == id }
            }
        }

        // 3. Return a custom MutableState
        return remember {
            object : MutableState<E> {
                override var value: E
                    get() = (localState.value ?: default())!!
                    set(newValue) {
                        localState.value = newValue
                        scope.launch {
                            dao.upsert(newValue) {
                                id = it
                            }
                        }
                    }

                // Standard boilerplate for MutableState implementation
                override fun component1(): E = value
                override fun component2(): (E) -> Unit = { value = it }
            }
        }
    }

    inline fun <reified E: DatabaseItem> upsert(t: E, noinline andThen: (Long) -> Unit = {}) {
        getDaoInterface<E>().upsert(t, andThen)
    }

    inline fun <reified E: DatabaseItem> delete(t: E, noinline andThen: (Int) -> Unit = {}) {
        getDaoInterface<E>().delete(t, andThen)
    }
}

abstract class DatabaseItem {
    abstract val id: Long

    fun isNew() = id == 0L
}

interface TrueDao<T: DatabaseItem> {
    fun getAll(): Flow<List<T>>
    @Upsert
    suspend fun upsert(value: T): Long
    @Delete
    suspend fun delete(value: T): Int
}

inline fun <reified T: RoomDatabase> Context.buildDatabase(): T {
    return Room.databaseBuilder(
        this,
        T::class.java,
        "passwords-db"
    ).build()
}