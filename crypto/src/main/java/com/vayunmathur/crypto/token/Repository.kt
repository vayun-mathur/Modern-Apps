package com.vayunmathur.crypto.token

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import androidx.core.content.edit

abstract class Repository<T : Any>(private val valueSerializer: KSerializer<T>) {
    protected val _data = MutableStateFlow<Map<String, T>>(emptyMap())
    val data: StateFlow<Map<String, T>> = _data

    protected abstract val sharedPreferencesName: String
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer by lazy { MapSerializer(String.serializer(), valueSerializer) }

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(sharedPreferencesName, Context.MODE_PRIVATE)
        val cachedData = sharedPreferences.getString("cached_data", null)
        if (cachedData != null) {
            try {
                val decodedData = json.decodeFromString(mapSerializer, cachedData)
                _data.value = decodedData
            } catch (e: Exception) {
                // Handle possible deserialization errors
                println("Failed to decode cached data for $sharedPreferencesName: ${e.message}")
            }
        }
    }

    operator fun get(tokenInfo: TokenInfo): T? {
        return _data.value[tokenInfo.mintAddress]
    }

    suspend fun update() {
        CoroutineScope(Dispatchers.IO).launch {
            val newData = getData()
            _data.value = newData
            saveData(newData)
        }.join()
    }

    private fun saveData(data: Map<String, T>) {
        if (::sharedPreferences.isInitialized) {
            val encodedData = json.encodeToString(mapSerializer, data)
            sharedPreferences.edit { putString("cached_data", encodedData) }
        }
    }

    protected abstract suspend fun getData(): Map<String, T>
}
