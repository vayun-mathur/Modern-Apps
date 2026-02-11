package com.vayunmathur.library.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

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

    fun getByteArray(name: String): ByteArray? {
        return stateMap[stringPreferencesKey(name)] as ByteArray?
    }

    suspend fun setByteArray(name: String, value: ByteArray, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if(onlyIfAbsent && it.contains(byteArrayPreferencesKey(name))) return@edit
            it[byteArrayPreferencesKey(name)] = value
        }
    }

    fun getLong(name: String): Long? {
        return stateMap[longPreferencesKey(name)] as Long?
    }

    @Composable
    fun booleanFlow(name: String): Flow<Boolean> {
        return dataStore.data.mapNotNull { it[booleanPreferencesKey(name)] }
    }

    suspend fun setBoolean(name: String, value: Boolean) {
        dataStore.edit {
            it[booleanPreferencesKey(name)] = value
        }
    }

    @Composable
    fun getLongState(name: String, default: Long = 0L): State<Long> {
        return dataStore.data.mapNotNull { it[longPreferencesKey(name)] }.collectAsState(default)
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
        return stateMap[stringPreferencesKey(string)] as String?
    }

    suspend fun setString(string: String, value: String, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if (onlyIfAbsent && it.contains(stringPreferencesKey(string))) return@edit
            it[stringPreferencesKey(string)]
        }
    }

    companion object {
        @Volatile
        private var instance: DataStoreUtils? = null

        fun getInstance(context: Context): DataStoreUtils {
            // First check (no locking for performance)
            return instance ?: synchronized(this) {
                // Second check (inside lock to ensure only one thread initializes)
                instance ?: DataStoreUtils(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

private fun createDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.filesDir.resolve("datastore_default.preferences_pb") }
