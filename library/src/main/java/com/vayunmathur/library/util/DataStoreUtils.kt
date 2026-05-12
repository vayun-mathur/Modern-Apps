package com.vayunmathur.library.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DataStoreUtils private constructor(context: Context) {
    private val dataStore = createDataStore(context)

    private var stateMap = mapOf<Preferences.Key<*>, Any>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.data.collect {
                stateMap = it.asMap()
            }
        }
    }

    private fun <T> getWithFallback(key: Preferences.Key<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return stateMap[key] as T?
    }

    private suspend fun initialize() {
        stateMap = dataStore.data.first().asMap()
    }


    fun getByteArray(name: String): ByteArray? {
        return getWithFallback(byteArrayPreferencesKey(name))
    }

    suspend fun setByteArray(name: String, value: ByteArray, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if(onlyIfAbsent && it.contains(byteArrayPreferencesKey(name))) return@edit
            it[byteArrayPreferencesKey(name)] = value
        }
    }

    fun getLong(name: String): Long? {
        return getWithFallback(longPreferencesKey(name))
    }

    fun booleanFlow(name: String): Flow<Boolean> {
        return dataStore.data.mapNotNull { it[booleanPreferencesKey(name)] }
    }

    suspend fun setBoolean(name: String, value: Boolean) {
        dataStore.edit {
            it[booleanPreferencesKey(name)] = value
        }
    }

    fun longFlow(s: String): Flow<Long> {
        return dataStore.data.mapNotNull { it[longPreferencesKey(s)] }
    }

    suspend fun setLong(s: String, userid: Long, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if(onlyIfAbsent && it.contains(longPreferencesKey(s))) return@edit
            it[longPreferencesKey(s)] = userid
        }
    }

    fun doubleFlow(string: String): Flow<Double> {
        return dataStore.data.mapNotNull { it[doublePreferencesKey(string)] }
    }

    suspend fun setDouble(string: String, progress: Double) {
        dataStore.edit {
            it[doublePreferencesKey(string)] = progress
        }
    }

    fun getString(string: String): String? {
        return getWithFallback(stringPreferencesKey(string))
    }

    suspend fun setString(string: String, value: String, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if (onlyIfAbsent && it.contains(stringPreferencesKey(string))) return@edit
            it[stringPreferencesKey(string)] = value
        }
    }

    fun stringFlow(key: String): Flow<String> {
        return dataStore.data.mapNotNull { it[stringPreferencesKey(key)] }
    }

    fun stringSetFlow(key: String): Flow<Set<String>> {
        return dataStore.data.map { it[stringSetPreferencesKey(key)] ?: emptySet() }
    }

    fun addStringToSet(string: String, id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit {
                val set = it[stringSetPreferencesKey(string)] ?: setOf()
                it[stringSetPreferencesKey(string)] = set + id
            }
        }
    }

    fun removeStringFromSet(string: String, id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit {
                val set = it[stringSetPreferencesKey(string)] ?: setOf()
                it[stringSetPreferencesKey(string)] = set - id
            }
        }
    }

    fun getBoolean(string: String, bool: Boolean): Boolean {
        return getWithFallback(booleanPreferencesKey(string)) ?: bool
    }

    companion object {
        @Volatile
        private var instance: DataStoreUtils? = null

        fun getInstance(context: Context): DataStoreUtils {
            // First check (no locking for performance)
            return instance ?: synchronized(this) {
                // Second check (inside lock to ensure only one thread initializes)
                instance ?: runBlocking {
                    DataStoreUtils(context.applicationContext).apply {
                        initialize()
                    }
                }.also {
                    instance = it
                }
            }
        }
    }
}

private fun createDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.filesDir.resolve("datastore_default.preferences_pb") }
