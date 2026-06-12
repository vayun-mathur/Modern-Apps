package com.vayunmathur.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "launcher_prefs")

class LauncherPreferences(private val context: Context) {
    companion object {
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val GRID_ROWS = intPreferencesKey("grid_rows")
        val PAGE_COUNT = intPreferencesKey("page_count")
        val CURRENT_PAGE = intPreferencesKey("current_page")
        val FIRST_RUN = booleanPreferencesKey("first_run")
    }

    val gridColumns: Flow<Int> = context.dataStore.data.map { it[GRID_COLUMNS] ?: 4 }
    val gridRows: Flow<Int> = context.dataStore.data.map { it[GRID_ROWS] ?: 5 }
    val pageCount: Flow<Int> = context.dataStore.data.map { it[PAGE_COUNT] ?: 1 }
    val currentPage: Flow<Int> = context.dataStore.data.map { it[CURRENT_PAGE] ?: 0 }
    val isFirstRun: Flow<Boolean> = context.dataStore.data.map { it[FIRST_RUN] ?: true }

    suspend fun setGridColumns(cols: Int) { context.dataStore.edit { it[GRID_COLUMNS] = cols } }
    suspend fun setGridRows(rows: Int) { context.dataStore.edit { it[GRID_ROWS] = rows } }
    suspend fun setPageCount(count: Int) { context.dataStore.edit { it[PAGE_COUNT] = count } }
    suspend fun setCurrentPage(page: Int) { context.dataStore.edit { it[CURRENT_PAGE] = page } }
    suspend fun setFirstRun(firstRun: Boolean) { context.dataStore.edit { it[FIRST_RUN] = firstRun } }
}
