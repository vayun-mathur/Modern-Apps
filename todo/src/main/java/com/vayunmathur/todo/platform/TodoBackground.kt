package com.vayunmathur.todo.platform

import android.content.Context
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.notifications.DigestScheduler
import com.vayunmathur.todo.notifications.NotificationChannels
import com.vayunmathur.todo.notifications.ReminderScheduler
import com.vayunmathur.todo.widget.TodayWidget
import com.vayunmathur.todo.sync.CalendarMirror
import androidx.glance.appwidget.updateAll
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide reactions to data changes, wherever they come from (UI, notification actions,
 * the relay provider): re-arm reminder alarms, the digest, and per-list channels.
 */
object TodoBackground {
    private val started = AtomicBoolean(false)
    private const val SETTLE_MS = 500L
    private const val MIRROR_SETTLE_MS = 2_000L

    private data class ReminderKey(val syncId: String, val reminderAt: Long?, val done: Boolean)

    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val repo = TodoRepository.get(app)
        val settings = TodoSettings(app)
        repo.appScope.launch {
            repo.todos
                .map { todos ->
                    todos.filter { it.reminderAt != null }.map { ReminderKey(it.syncId, it.reminderAt, it.done) }
                }
                .distinctUntilChanged()
                .debounce(SETTLE_MS)
                .collect { ReminderScheduler.reconcile(app, repo.getAll()) }
        }
        repo.appScope.launch {
            combine(settings.digestEnabled, settings.digestMinutes) { on, minutes -> on to minutes }
                .distinctUntilChanged()
                .collect { (on, minutes) -> DigestScheduler.schedule(app, on, minutes) }
        }
        repo.appScope.launch {
            repo.lists.distinctUntilChanged().collect { NotificationChannels.syncLists(app, it) }
        }
        repo.appScope.launch {
            combine(repo.todos, repo.lists) { _, _ -> }.debounce(SETTLE_MS).collect { refreshWidgets(app) }
        }
        repo.appScope.launch {
            combine(repo.todos, settings.calendarMirror) { todos, on -> todos to on }
                .debounce(MIRROR_SETTLE_MS)
                .collect { (todos, on) -> CalendarMirror.reconcile(app, on, todos) }
        }
    }

    // Broad catch: a widget host failure must never take the app process down.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshWidgets(context: Context) {
        try {
            TodayWidget().updateAll(context)
        } catch (e: Exception) {
            Log.error(TAG, "widget refresh failed", e)
        }
    }

    private const val TAG = "TodoBackground"

    /** Re-arms everything now; for boot, updates and clock changes. */
    suspend fun reconcileNow(context: Context) {
        val app = context.applicationContext
        val repo = TodoRepository.get(app)
        val settings = TodoSettings(app)
        ReminderScheduler.reconcile(app, repo.getAll())
        DigestScheduler.schedule(app, settings.digestEnabledNow(), settings.digestMinutesNow())
    }
}
