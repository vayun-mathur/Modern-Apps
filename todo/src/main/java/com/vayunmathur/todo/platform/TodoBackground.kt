package com.vayunmathur.todo.platform

import android.content.Context
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.notifications.DigestScheduler
import com.vayunmathur.todo.notifications.NotificationChannels
import com.vayunmathur.todo.notifications.ReminderScheduler
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

    private data class ReminderKey(val syncId: String, val reminderAt: Long?, val done: Boolean)

    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val repo = TodoRepository.get(app)
        val settings = TodoSettings(app)
        repo.appScope.launch {
            repo.todos
                .map { todos -> todos.filter { it.reminderAt != null }.map { ReminderKey(it.syncId, it.reminderAt, it.done) } }
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
    }

    /** Re-arms everything now; for boot, updates and clock changes. */
    suspend fun reconcileNow(context: Context) {
        val app = context.applicationContext
        val repo = TodoRepository.get(app)
        val settings = TodoSettings(app)
        ReminderScheduler.reconcile(app, repo.getAll())
        DigestScheduler.schedule(app, settings.digestEnabledNow(), settings.digestMinutesNow())
    }
}
