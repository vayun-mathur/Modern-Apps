package com.vayunmathur.maps.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.vayunmathur.maps.data.google.GoogleReview
import com.vayunmathur.maps.data.google.ReviewsWebParser
import com.vayunmathur.maps.service.ReviewsScraperService.Companion.KEY_FEATURE_ID
import com.vayunmathur.maps.service.ReviewsScraperService.Companion.KEY_JSON
import com.vayunmathur.maps.service.ReviewsScraperService.Companion.MSG_CANCEL
import com.vayunmathur.maps.service.ReviewsScraperService.Companion.MSG_FETCH
import com.vayunmathur.maps.service.ReviewsScraperService.Companion.MSG_PARTIAL
import com.vayunmathur.maps.service.ReviewsScraperService.Companion.MSG_RESULT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Map-process side of [ReviewsScraperService]: binds on demand, streams parsed reviews back, and
 * unbinds after [IDLE_UNBIND_MS] without requests so the `:reviews` process (and Chromium) exits.
 * Best-effort like the scraper itself: any IPC failure yields an empty list.
 */
class ReviewsScraperClient(context: Context) {
    private sealed interface Event {
        data class Partial(val json: String) : Event
        data class Result(val json: String?) : Event
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val requests = ConcurrentHashMap<Int, Channel<Event>>()
    private val seq = AtomicInteger()
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { onReply(it); true })
    private val idleUnbind = Runnable { disconnect() }

    // Main thread only.
    private var remote: CompletableDeferred<Messenger?>? = null
    private var connection: ServiceConnection? = null

    suspend fun fetch(featureId: String, onPartial: (List<GoogleReview>) -> Unit): List<GoogleReview> {
        val id = seq.incrementAndGet()
        val events = Channel<Event>(Channel.UNLIMITED)
        requests[id] = events
        var service: Messenger? = null
        try {
            val svc = withContext(Dispatchers.Main) {
                main.removeCallbacks(idleUnbind)
                connect()
            }.await() ?: return emptyList()
            service = svc
            val msg = Message.obtain(null, MSG_FETCH, id, 0)
            msg.data = Bundle().apply { putString(KEY_FEATURE_ID, featureId) }
            msg.replyTo = incoming
            svc.send(msg)
            for (event in events) {
                when (event) {
                    is Event.Partial -> ReviewsWebParser.parse(event.json).takeIf { it.isNotEmpty() }?.let(onPartial)
                    is Event.Result -> return event.json?.let(ReviewsWebParser::parse).orEmpty()
                }
            }
            return emptyList()
        } catch (_: RemoteException) {
            return emptyList()
        } finally {
            requests.remove(id)
            withContext(NonCancellable + Dispatchers.Main) {
                // No-op once the request already finished; frees the scrape slot if it was abandoned.
                runCatching { service?.send(Message.obtain(null, MSG_CANCEL, id, 0)) }
                if (requests.isEmpty() && connection != null) main.postDelayed(idleUnbind, IDLE_UNBIND_MS)
            }
        }
    }

    private fun connect(): CompletableDeferred<Messenger?> {
        remote?.let { return it }
        val deferred = CompletableDeferred<Messenger?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                deferred.complete(binder?.let(::Messenger))
            }
            override fun onServiceDisconnected(name: ComponentName?) = onLost()
            override fun onBindingDied(name: ComponentName?) = onLost()
            override fun onNullBinding(name: ComponentName?) = onLost()
        }
        remote = deferred
        connection = conn
        val bound = runCatching {
            app.bindService(Intent(app, ReviewsScraperService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) disconnect()
        return deferred
    }

    /** The `:reviews` process died mid-session: fail in-flight requests, rebind on the next fetch. */
    private fun onLost() {
        requests.values.forEach { it.trySend(Event.Result(null)) }
        disconnect()
    }

    private fun disconnect() {
        main.removeCallbacks(idleUnbind)
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        remote?.complete(null)
        remote = null
    }

    private fun onReply(msg: Message) {
        val events = requests[msg.arg1] ?: return
        val json = msg.data.getString(KEY_JSON)
        when (msg.what) {
            MSG_PARTIAL -> json?.let { events.trySend(Event.Partial(it)) }
            MSG_RESULT -> events.trySend(Event.Result(json))
        }
    }

    private companion object {
        // Keep the scraper warm across back-to-back place taps, then let the process exit.
        const val IDLE_UNBIND_MS = 120_000L
    }
}
