package com.vayunmathur.photos.platform

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Registration seam for [ForegroundMediaObserver]: production talks to the
 * real [ContentResolver]; tests substitute a recording fake so the
 * register/unregister lifecycle can be asserted without a provider.
 */
interface MediaStoreRegistrar {
    fun register(observer: ContentObserver, uri: Uri, notifyForDescendants: Boolean)
    fun unregister(observer: ContentObserver)
}

/** [MediaStoreRegistrar] over the real [ContentResolver]. */
class AndroidMediaStoreRegistrar(
    private val resolver: ContentResolver,
) : MediaStoreRegistrar {
    override fun register(observer: ContentObserver, uri: Uri, notifyForDescendants: Boolean) {
        resolver.registerContentObserver(uri, notifyForDescendants, observer)
    }

    override fun unregister(observer: ContentObserver) {
        resolver.unregisterContentObserver(observer)
    }
}

/**
 * MediaStore content observer active only while the gallery is foregrounded.
 *
 * Before this, a just-taken photo waited for the WorkManager
 * content-triggered [SyncWorker] (OS scheduling + the unique-work queue, and
 * then that worker's inline EXIF backlog) before its row reached Room.
 * Registering a direct observer on start and unregistering it on stop makes
 * the foregrounded app react within [debounceMs] instead: changes are
 * coalesced (a burst from one camera capture fires several callbacks),
 * de-duplicated by URI, and handed to [onTriggered] as item URIs for the
 * triggered-URI incremental sync ([syncTriggeredPhotos]). Collection URIs
 * and null callbacks are dropped by that sync, never widened to a full scan.
 *
 * Lifecycle contract: [start] registers exactly the image + video
 * collections once (repeat starts are no-ops), [stop] unregisters once,
 * cancels any pending debounced import, and makes late callbacks no-ops —
 * so a stopped observer leaks neither a registration nor a pending import.
 * A later [start] re-registers cleanly.
 */
class ForegroundMediaObserver(
    private val registrar: MediaStoreRegistrar,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onTriggered: suspend (List<Uri>) -> Unit,
) {
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged(uri)
        }

        override fun onChange(selfChange: Boolean) {
            // No URI to trigger on: ignoring it is what keeps the foreground
            // path from ever falling back to a full scan.
            onMediaChanged(null)
        }
    }

    /** True between a successful [start] and its matching [stop]. */
    var isRegistered: Boolean = false
        private set

    private val pending = linkedSetOf<Uri>()
    private var pendingJob: Job? = null

    fun start() {
        if (isRegistered) return
        OBSERVED_URIS.forEach { uri -> registrar.register(observer, uri, true) }
        isRegistered = true
    }

    fun stop() {
        if (!isRegistered) return
        isRegistered = false
        registrar.unregister(observer)
        pendingJob?.cancel()
        pendingJob = null
        synchronized(pending) { pending.clear() }
    }

    /**
     * One MediaStore change callback. Visible for tests; the production
     * entry point is the [ContentObserver] above.
     */
    fun onMediaChanged(uri: Uri?) {
        if (!isRegistered || uri == null) return
        synchronized(pending) { pending += uri }
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMs)
            val batch = synchronized(pending) {
                pending.toList().also { pending.clear() }
            }
            if (batch.isEmpty()) return@launch
            try {
                onTriggered(batch)
            } catch (e: Exception) {
                // A failed fast import must not kill the observer: the
                // background WorkManager trigger remains as the safety net,
                // and the next change retries.
                Log.error(TAG, "Foreground media import failed for $batch", e)
            }
        }
    }

    /** Shared constants. */
    companion object {
        private const val TAG = "ForegroundMediaObserver"

        /** Coalescing window for a burst of callbacks from one capture. */
        const val DEFAULT_DEBOUNCE_MS = 400L

        /** Image + video collections watched while foregrounded. */
        val OBSERVED_URIS: List<Uri> = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
    }
}
