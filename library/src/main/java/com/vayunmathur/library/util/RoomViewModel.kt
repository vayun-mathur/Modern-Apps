package com.vayunmathur.library.util

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
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

interface TrueDao<T: DatabaseItem> {
    fun getAll(): Flow<List<T>>
    suspend fun upsert(value: T): Long
    suspend fun delete(value: T): Int
}

inline fun <reified T: RoomDatabase> Context.buildDatabase(): T {
    return Room.databaseBuilder(
        this,
        T::class.java,
        "passwords-db"
    ).build()
}