package com.vayunmathur.maps.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.vayunmathur.maps.data.google.WebReviewsFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Hosts [WebReviewsFetcher] in the `:reviews` process. Chromium's ~100 MB of mappings stay pinned
 * for the life of whichever process first creates a WebView, so the scraper lives here and the
 * process exits once [ReviewsScraperClient] unbinds instead of bloating the map process forever.
 */
class ReviewsScraperService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val fetcher by lazy { WebReviewsFetcher(applicationContext) }
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // Nothing else runs in this process; a cached-but-idle process would keep Chromium resident.
        Process.killProcess(Process.myPid())
    }

    private fun handle(msg: Message) {
        val id = msg.arg1
        when (msg.what) {
            MSG_FETCH -> {
                val reply = msg.replyTo ?: return
                val featureId = msg.data.getString(KEY_FEATURE_ID) ?: return
                val job = scope.launch {
                    val json = fetcher.fetch(featureId, onPartial = { reply.post(MSG_PARTIAL, id, it) })
                    reply.post(MSG_RESULT, id, json)
                }
                jobs[id] = job
                job.invokeOnCompletion { jobs.remove(id, job) }
            }
            MSG_CANCEL -> jobs.remove(id)?.cancel()
        }
    }

    private fun Messenger.post(what: Int, id: Int, json: String?) {
        val msg = Message.obtain(null, what, id, 0)
        msg.data = Bundle().apply { putString(KEY_JSON, json) }
        try {
            send(msg)
        } catch (_: RemoteException) {
            // Client process died; its unbind tears this service down.
        }
    }

    internal companion object {
        const val MSG_FETCH = 1
        const val MSG_CANCEL = 2
        const val MSG_PARTIAL = 3
        const val MSG_RESULT = 4
        const val KEY_FEATURE_ID = "featureId"
        const val KEY_JSON = "json"
    }
}
