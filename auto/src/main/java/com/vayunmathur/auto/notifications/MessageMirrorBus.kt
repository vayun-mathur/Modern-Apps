package com.vayunmathur.auto.notifications

import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide bus between the notification listener and the projection session.
 *
 * `MessageMirrorService` lives and dies with the system's listener binding,
 * while the ch14 owner lives with the GAL session; neither may hold the other.
 * The service posts snapshots here, and `ProjectionService` registers one
 * listener per session and unregisters it when the head unit goes away.
 * Thread-safe: posts arrive on the listener binder thread, sends leave on the
 * projection worker thread.
 */
object MessageMirrorBus {
    /** Callback receiving mirrored message threads for a projection session. */
    fun interface Listener {
        fun onMirror(thread: MessagingThread, message: MessagingMessage, route: MessageReplyRoute?)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Registers [listener]; closing the return unregisters it. */
    fun register(listener: Listener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun post(thread: MessagingThread, message: MessagingMessage, route: MessageReplyRoute? = null) {
        for (listener in listeners) listener.onMirror(thread, message, route)
    }
}
