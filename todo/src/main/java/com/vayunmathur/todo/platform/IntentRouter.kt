package com.vayunmathur.todo.platform

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What an incoming intent asks the UI to do — open a task, open a view, or start a new task
 * with some text. MainActivity records it; the nav graph consumes it once.
 */
object IntentRouter {
    const val EXTRA_OPEN_TASK = "com.vayunmathur.todo.extra.OPEN_TASK"
    const val EXTRA_VIEW = "com.vayunmathur.todo.extra.VIEW"
    const val ACTION_QUICK_ADD = "com.vayunmathur.todo.action.QUICK_ADD"

    sealed interface Request {
        data class OpenTask(val syncId: String) : Request
        data class OpenView(val viewKey: String) : Request

        /** A new task; [text] pre-fills quick add, several lines become several tasks. */
        data class QuickAdd(val text: String) : Request
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending

    fun handle(intent: Intent?) {
        _pending.value = parse(intent ?: return) ?: return
    }

    fun consume() {
        _pending.value = null
    }

    private fun parse(intent: Intent): Request? {
        intent.getStringExtra(EXTRA_OPEN_TASK)?.let { return Request.OpenTask(it) }
        intent.getStringExtra(EXTRA_VIEW)?.let { return Request.OpenView(it) }
        return when (intent.action) {
            ACTION_QUICK_ADD -> Request.QuickAdd(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                Request.QuickAdd(if (subject != null && subject !in text) "$subject $text" else text)
            }
            else -> null
        }
    }
}
