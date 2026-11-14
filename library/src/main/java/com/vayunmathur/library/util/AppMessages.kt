package com.vayunmathur.library.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide channel for transient messages, drained by `MainNavigation` onto
 * the snackbar.
 *
 * Exists because Toast is banned (see the `ToastUsage` lint check) and the
 * obvious replacement, a snackbar, needs a composition to live in. Plenty of
 * the places that need to say something - a ViewModel finishing a save, a
 * worker reporting a failure - have no composition and often no Context
 * either. Rather than thread a callback down to each of them, they post here
 * and whichever screen is on top shows it.
 *
 * Replay is zero and the buffer drops oldest: a message posted while no screen
 * is listening is gone rather than queued to ambush the next screen that
 * opens. Anything that must survive that - a completed download, a failed
 * sync - is a notification, not a transient message.
 */
object AppMessages {

    /** How long a transient message stays on screen. */
    enum class Duration { Short, Long, Indefinite }

    /** One transient message: text, an optional action and a display duration. */
    data class Message(
        val text: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
        val duration: Duration = Duration.Short,
    )

    private val _messages = MutableSharedFlow<Message>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    /** Window within which an identical, actionless message is suppressed as a repeat. */
    private const val DEDUP_WINDOW_MS = 3_000L
    private var lastText: String? = null
    private var lastAtMs: Long = 0L

    /**
     * Post a message from anywhere - ViewModel, worker, or a plain Activity.
     *
     * Never suspends and never fails; if nothing is collecting, the message is
     * dropped.
     *
     * Identical actionless messages posted within [DEDUP_WINDOW_MS] are dropped so a
     * repeatedly-failing operation (e.g. an update retrying, or a per-package install
     * callback firing for a batch) doesn't spam the same snackbar. See issue #630.
     */
    fun show(
        text: String,
        actionLabel: String? = null,
        duration: Duration = Duration.Short,
        onAction: (() -> Unit)? = null
    ) {
        if (actionLabel == null) {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (text == lastText && now - lastAtMs < DEDUP_WINDOW_MS) return
                lastText = text
                lastAtMs = now
            }
        }
        _messages.tryEmit(Message(text, actionLabel, onAction, duration))
    }
}
