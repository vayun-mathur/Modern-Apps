package com.vayunmathur.todo.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** App preferences, in the shared `datastore_default` file the backup rules already carry. */
class TodoSettings(context: Context) {
    private val store = DataStoreUtils.getInstance(context.applicationContext)

    private fun string(key: String, default: String): Flow<String> =
        store.stringFlow(key).onStart { emit(store.getString(key) ?: default) }

    val defaultView: Flow<TodoView> = string(KEY_DEFAULT_VIEW, TodoView.Today.key).map { TodoView.fromKey(it) }
    val defaultSort: Flow<SortMode> = string(KEY_DEFAULT_SORT, SortMode.MANUAL.name)
        .map { runCatching { SortMode.valueOf(it) }.getOrDefault(SortMode.MANUAL) }
    val showCompleted: Flow<Boolean> = store.booleanFlow(KEY_SHOW_COMPLETED, true)
    val digestEnabled: Flow<Boolean> = store.booleanFlow(KEY_DIGEST, false)

    /** Minutes after midnight for the daily digest. */
    val digestMinutes: Flow<Long> = store.longFlow(KEY_DIGEST_MINUTES, DEFAULT_DIGEST_MINUTES)
    val calendarMirror: Flow<Boolean> = store.booleanFlow(KEY_CALENDAR_MIRROR, false)

    /** Epoch ms of the last relay call from everysync, 0 when never. */
    val lastSyncAt: Flow<Long> = store.longFlow(KEY_LAST_SYNC, 0L)

    suspend fun setDefaultView(view: TodoView) = store.setString(KEY_DEFAULT_VIEW, view.key)
    suspend fun setDefaultSort(sort: SortMode) = store.setString(KEY_DEFAULT_SORT, sort.name)
    suspend fun setShowCompleted(value: Boolean) = store.setBoolean(KEY_SHOW_COMPLETED, value)
    suspend fun setDigestEnabled(value: Boolean) = store.setBoolean(KEY_DIGEST, value)
    suspend fun setDigestMinutes(value: Long) = store.setLong(KEY_DIGEST_MINUTES, value)
    suspend fun setCalendarMirror(value: Boolean) = store.setBoolean(KEY_CALENDAR_MIRROR, value)
    suspend fun markSynced(at: Long = System.currentTimeMillis()) = store.setLongIfGreater(KEY_LAST_SYNC, at)

    suspend fun digestEnabledNow(): Boolean = digestEnabled.first()
    suspend fun digestMinutesNow(): Long = digestMinutes.first()
    suspend fun calendarMirrorNow(): Boolean = calendarMirror.first()

    companion object {
        private const val KEY_DEFAULT_VIEW = "todo_default_view"
        private const val KEY_DEFAULT_SORT = "todo_default_sort"
        private const val KEY_SHOW_COMPLETED = "todo_show_completed"
        private const val KEY_DIGEST = "todo_digest"
        private const val KEY_DIGEST_MINUTES = "todo_digest_minutes"
        private const val KEY_CALENDAR_MIRROR = "todo_calendar_mirror"
        private const val KEY_LAST_SYNC = "todo_last_sync"
        const val DEFAULT_DIGEST_MINUTES = 8L * 60
    }
}
