package com.vayunmathur.todo.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.vayunmathur.library.util.deleteNotificationChannel
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.TodoList

/**
 * One reminder channel per list (plus the Inbox), so each list's sound, importance and
 * Do Not Disturb exception can be set separately in system settings; a separate channel for
 * the daily digest.
 */
object NotificationChannels {
    private const val LIST_PREFIX = "todo_list_"
    const val INBOX = "todo_inbox"
    const val DIGEST = "todo_digest"
    private const val HIGH = NotificationManager.IMPORTANCE_HIGH
    private const val DEFAULT = NotificationManager.IMPORTANCE_DEFAULT

    fun forList(listSyncId: String?): String = listSyncId?.let { LIST_PREFIX + it } ?: INBOX

    fun ensureInbox(context: Context) =
        context.ensureNotificationChannel(INBOX, context.getString(R.string.view_inbox), HIGH)

    fun ensureDigest(context: Context) =
        context.ensureNotificationChannel(DIGEST, context.getString(R.string.digest_channel), DEFAULT)

    /** Creates/renames a channel per list and removes channels of deleted lists. */
    fun syncLists(context: Context, lists: List<TodoList>) {
        ensureInbox(context)
        val wanted = lists.associateBy { forList(it.syncId) }
        wanted.forEach { (id, list) ->
            context.ensureNotificationChannel(id, list.name.ifBlank { context.getString(R.string.untitled) }, HIGH)
        }
        context.getSystemService<NotificationManager>()?.notificationChannels.orEmpty()
            .map { it.id }
            .filter { it.startsWith(LIST_PREFIX) && it !in wanted }
            .forEach { context.deleteNotificationChannel(it) }
    }
}
