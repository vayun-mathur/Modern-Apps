package com.vayunmathur.share.platform.receive

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.vayunmathur.library.util.deleteNotificationChannel
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.share.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.IncomingTransfer
import com.vayunmathur.share.platform.ReceivedFile
import com.vayunmathur.share.platform.ReceivedFileStore
import com.vayunmathur.share.platform.transfer.ShareTransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ShareNotifier"

/**
 * Everything the user is meant to react to — requests, progress, and outcomes.
 *
 * Both `IMPORTANCE_MAX`, so all of them heads-up rather than sitting in the shade. The only
 * quiet notification `:share` posts is the foreground service's own "Ready to receive", which
 * has its own `IMPORTANCE_LOW` channel in [ShareTransferService]; the two are deliberately
 * separate so silencing the always-on status indicator does not silence an incoming transfer.
 *
 * [CHANNEL_REQUESTS] carries a `_v2` suffix because the first cut of it was `IMPORTANCE_HIGH`,
 * and a channel's importance is fixed for good: `createNotificationChannel` refreshes a
 * channel's name and description, but the system ignores any later change to its importance
 * and remembers the settings of deleted channels, so raising it needs a new id.
 */
private const val CHANNEL_REQUESTS = "share_requests_v2"
private const val CHANNEL_TRANSFERS = "share_transfers"

/** The `IMPORTANCE_HIGH` requests channel that [CHANNEL_REQUESTS] replaces. */
private const val CHANNEL_LEGACY_REQUESTS = "share_requests"

/** Concurrent transfers bundle under one summary instead of stacking. */
private const val GROUP_KEY = "com.vayunmathur.share.TRANSFERS"

/**
 * First id handed out per transfer. Above the foreground-service notification's 4101 and
 * allocated from a counter rather than hashed from the session handle: hashing a `Long` into
 * an `Int` invites a silent collision between two concurrent transfers, which would show one
 * peer's files under the other's name.
 */
private const val FIRST_TRANSFER_NOTIF_ID = 4200

/** GMS's measured accept timeout. A prompt that outlives the session is a lie. */
private const val REQUEST_TIMEOUT_MS = 60_000L

/** Android 16 Live Updates; below this a plain determinate bar says the same thing. */
private const val PROGRESS_STYLE_SDK = 36

/** Bytes per kilobyte, for notification progress text. */
private const val BYTES_PER_KB = 1024L

/** Notification progress scale: percent. */
private const val PROGRESS_MAX = 100

/** Standard hash multiplier for combining request-code parts. */
private const val HASH_MULTIPLIER = 31

/**
 * Longest edge of the preview bitmap in a received-image notification.
 *
 * A notification travels to the system by Binder, so the bitmap it carries has to stay small:
 * a full-resolution photo is both a `TransactionTooLargeException` waiting to happen and a
 * pointless allocation, since `BigPictureStyle` never renders more than a strip of it.
 */
private const val MAX_PREVIEW_PX = 1024

/**
 * Request codes reserved per notification, so Share and Save cannot collide.
 *
 * Two per notification id: `id * N` for Share and `id * N + 1` for Save.
 */
private const val REQUEST_CODES_PER_NOTIF = 2

/**
 * Turns incoming transfers — Quick Share and AirDrop alike — into notifications, which are the
 * only receive UI there is.
 *
 * Owned by [ShareReceiveController] rather than by [ShareTransferService]: the service can be
 * recreated at any time, and two overlapping instances would double-post.
 *
 * Concurrency shape worth keeping: a **keyed child job per session handle**, not
 * `flatMapLatest { merge(...) }`. The latter tears down and restarts every existing collector
 * whenever a new connection appears, so a second peer arriving would restart the first
 * transfer's observer mid-flight. Each child `conflate()`s, so a notification post can never
 * back-pressure the transfer pump.
 */
class ShareReceiveNotifier(
    context: Context,
    private val incoming: Flow<List<IncomingTransfer>>,
    private val retire: (Long) -> Unit,
    /**
     * Called with the session handle after a notification for it has
     * been posted (request, progress, done or failed). A terminal-state
     * signal for observers — notably tests — that need to know a post
     * landed instead of polling the notification shade. Default no-op
     * keeps production wiring unchanged.
     */
    private val onNotificationPosted: (Long) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private var job: Job? = null

    /** Allocated notification id per handle, and whose terminal post has landed. [idLock]. */
    private val ids = mutableMapOf<Long, Int>()
    private val terminalPosted = mutableSetOf<Long>()
    private var nextId = FIRST_TRANSFER_NOTIF_ID
    private val idLock = Any()

    private enum class Kind { None, Request, Progress, Done, Failed }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        ensureChannels()
        job = scope.launch { watch() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun ensureChannels() {
        appContext.deleteNotificationChannel(CHANNEL_LEGACY_REQUESTS)
        appContext.ensureNotificationChannel(
            id = CHANNEL_REQUESTS,
            name = appContext.getString(R.string.share_channel_requests_name),
            importance = NotificationManager.IMPORTANCE_MAX,
            description = appContext.getString(R.string.share_channel_requests_desc),
        )
        appContext.ensureNotificationChannel(
            id = CHANNEL_TRANSFERS,
            name = appContext.getString(R.string.share_channel_transfers_name),
            importance = NotificationManager.IMPORTANCE_MAX,
            description = appContext.getString(R.string.share_channel_transfers_desc),
        )
    }

    private suspend fun watch() {
        coroutineScope {
            val children = mutableMapOf<Long, Job>()
            incoming.collect { conns ->
                val live = conns.associateBy { it.sessionHandle }
                for ((handle, conn) in live) {
                    if (children.containsKey(handle)) continue
                    children[handle] = launch { observe(conn) }
                }
                for (handle in children.keys - live.keys) {
                    children.remove(handle)?.cancel()
                    onGone(handle)
                }
            }
        }
    }

    /**
     * One notification lifecycle for one connection.
     *
     * `peerName`, `expectedTotalBytes` and `pendingFiles` are read at post time rather than
     * combined: all three are set before the state they matter for, so folding them into the
     * `combine` would only add emissions that change nothing — and an emission ordered ahead
     * of the one that carries the file list reported an incoming transfer of no files at all.
     */
    private suspend fun observe(conn: IncomingTransfer) {
        val gate = PostGate()
        combine(
            conn.state,
            conn.receivedFiles,
            conn.bytesReceived,
        ) { state, received, bytes -> Snapshot(state, received, bytes) }
            .conflate()
            .collect { snap ->
                val kind = kindOf(snap.state)
                if (kind == Kind.None) return@collect
                val total = conn.expectedTotalBytes.value
                val percent = if (total > 0) ((snap.bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                if (!gate.admit(kind.ordinal, percent, System.currentTimeMillis())) return@collect
                post(conn, kind, snap, percent)
            }
    }

    private class Snapshot(
        val state: ShareState,
        val received: List<ReceivedFile>,
        val bytes: Long,
    )

    private fun kindOf(state: ShareState): Kind = when (state) {
        ShareState.AwaitingAccept -> Kind.Request
        ShareState.Transferring -> Kind.Progress
        ShareState.Completed -> Kind.Done
        ShareState.Failed -> Kind.Failed
        ShareState.Handshaking, ShareState.Unknown -> Kind.None
    }

    /**
     * A connection vanished from the transport.
     *
     * Only reported when the user was already shown something for it: a session that failed
     * during the handshake was never visible, and announcing a failed transfer nobody
     * initiated is noise.
     */
    private fun onGone(handle: Long) {
        val id = synchronized(idLock) {
            val id = ids.remove(handle) ?: return
            if (terminalPosted.remove(handle)) return
            id
        }
        notify(id, failedNotification(appContext.getString(R.string.share_transfer_failed)))
    }

    private fun idFor(handle: Long): Int = synchronized(idLock) {
        ids.getOrPut(handle) { nextId++ }
    }

    private suspend fun post(conn: IncomingTransfer, kind: Kind, snap: Snapshot, percent: Int) {
        val handle = conn.sessionHandle
        val id = idFor(handle)
        val notification = when (kind) {
            Kind.Request -> requestNotification(conn, id)
            Kind.Progress -> progressNotification(conn, id, snap, percent)
            Kind.Done -> doneNotification(conn, id, snap.received)
            Kind.Failed -> failedNotification(conn.error.value ?: appContext.getString(R.string.share_transfer_failed))
            Kind.None -> return
        }
        if (kind == Kind.Done || kind == Kind.Failed) {
            synchronized(idLock) { terminalPosted += handle }
            notify(id, notification)
            onNotificationPosted(handle)
            // The user has been told the outcome, so the session has no remaining reader.
            // Nothing else disconnects an incoming session.
            retire(handle)
            return
        }
        notify(id, notification)
        onNotificationPosted(handle)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(appContext).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-transfer. Nothing to fall back to — the tile
            // refuses to enable without it — so record it rather than crashing the pump.
            Log.status(TAG, "cannot post notification $id", e)
        }
    }

    private fun base(channel: String) = NotificationCompat.Builder(appContext, channel)
        .setSmallIcon(R.drawable.ic_tile_share)
        .setGroup(GROUP_KEY)

    private fun requestNotification(conn: IncomingTransfer, id: Int): android.app.Notification {
        // A count of zero is a count we do not have: an announcement of text or a link carries
        // no file metadata at all, so say something true instead of promising no files.
        val fileCount = conn.pendingFiles.value.size
        val text = if (fileCount > 0) {
            appContext.resources.getQuantityString(R.plurals.share_wants_to_send, fileCount, fileCount)
        } else {
            appContext.getString(R.string.share_wants_to_send_unknown)
        }
        return base(CHANNEL_REQUESTS)
            .setContentTitle(conn.displayName)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setOngoing(true)
            .setTimeoutAfter(REQUEST_TIMEOUT_MS)
            .addAction(
                R.drawable.ic_tile_share,
                appContext.getString(R.string.share_accept),
                sessionAction(ShareTransferService.ACTION_ACCEPT, conn.sessionHandle, id),
            )
            .addAction(
                R.drawable.ic_tile_share,
                appContext.getString(R.string.share_decline),
                sessionAction(ShareTransferService.ACTION_REJECT, conn.sessionHandle, id),
            )
            .build()
    }

    private fun progressNotification(
        conn: IncomingTransfer,
        id: Int,
        snap: Snapshot,
        percent: Int,
    ): android.app.Notification {
        val total = conn.expectedTotalBytes.value
        val builder = base(CHANNEL_TRANSFERS)
            .setContentTitle(conn.displayName)
            .setContentText(appContext.getString(R.string.share_receiving))
            .setSubText(
                appContext.getString(R.string.share_progress_kb, snap.bytes / BYTES_PER_KB, total / BYTES_PER_KB)
            )
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_tile_share,
                appContext.getString(UiR.string.cancel),
                sessionAction(ShareTransferService.ACTION_CANCEL, conn.sessionHandle, id),
            )
        val sizesKb = conn.pendingFiles.value.map { (it.sizeBytes / BYTES_PER_KB).toInt().coerceAtLeast(1) }
        if (Build.VERSION.SDK_INT >= PROGRESS_STYLE_SDK && sizesKb.isNotEmpty()) {
            // One segment per announced file, so a multi-file transfer shows which file it is on.
            builder.setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgressSegments(sizesKb.map { NotificationCompat.ProgressStyle.Segment(it) })
                    .setProgress((snap.bytes / BYTES_PER_KB).toInt())
            )
            builder.setShortCriticalText("$percent%")
        } else {
            builder.setProgress(PROGRESS_MAX, percent, total <= 0)
        }
        return builder.build()
    }

    private suspend fun doneNotification(
        conn: IncomingTransfer,
        id: Int,
        received: List<ReceivedFile>,
    ): android.app.Notification {
        val builder = base(CHANNEL_TRANSFERS)
            .setContentTitle(conn.displayName)
            .setContentText(
                appContext.resources.getQuantityString(R.plurals.share_received_count, received.size, received.size),
            )
            .setAutoCancel(true)
        previewBitmap(received)?.let { preview ->
            // `bigLargeIcon(null)` so expanding drops the thumbnail instead of showing the same
            // image twice; collapsed, the large icon is the only preview there is.
            builder.setLargeIcon(preview)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(preview)
                    .bigLargeIcon(null as Bitmap?)
            )
        }
        if (received.isNotEmpty()) {
            // URIs, not the handle: by the time this is tapped the session is gone.
            val uris = received.map { it.uri }
            val mime = received.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
            builder.addAction(
                R.drawable.ic_tile_share,
                appContext.getString(R.string.app_name),
                sharePendingIntent(uris, mime, id),
            )
            builder.addAction(
                R.drawable.ic_tile_share,
                appContext.getString(UiR.string.save),
                savePendingIntent(uris, id),
            )
        }
        return builder.build()
    }

    /**
     * A downscaled preview of the first received image, or null if there is none to show.
     *
     * Decoded straight to [MAX_PREVIEW_PX] rather than loaded and then scaled, so a large photo
     * never exists at full size in this process. Any failure — an unsupported codec, a truncated
     * file, a type that only claims to be an image — falls back to the plain notification, which
     * still names the file and offers Share and Save.
     */
    private suspend fun previewBitmap(received: List<ReceivedFile>): Bitmap? =
        withContext(Dispatchers.IO) {
            received.filter(::couldBeImage).firstNotNullOfOrNull(::decodePreview)
        }

    /**
     * Whether [file] is worth handing to [ImageDecoder].
     *
     * Deliberately not `mimeType.startsWith("image/")`: a GMS peer announces no MIME type for
     * its attachments and names their payloads without one either, so the file arrives as
     * [ReceivedFileStore.GENERIC_MIME_TYPE] with no extension to correct it, and gating on the
     * type alone meant a photo from Quick Share never showed a preview. An absence of evidence
     * is treated as a maybe and settled by the decode; a type that positively says otherwise —
     * a video, a PDF — is not decoded at all.
     */
    private fun couldBeImage(file: ReceivedFile): Boolean {
        if (file.mimeType.startsWith("image/")) return true
        val byExtension = ReceivedFileStore.mimeTypeOf(file.name)
        if (byExtension.startsWith("image/")) return true
        return file.mimeType == ReceivedFileStore.GENERIC_MIME_TYPE &&
            byExtension == ReceivedFileStore.GENERIC_MIME_TYPE
    }

    // Broad catch is deliberate: image decoding throws undocumented RuntimeExceptions
    // (not just IOException) on corrupt files, which read as "no preview".
    @Suppress("TooGenericExceptionCaught")
    private fun decodePreview(image: ReceivedFile): Bitmap? = try {
        val source = ImageDecoder.createSource(appContext.contentResolver, image.uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_PREVIEW_PX) {
                val ratio = longest.toFloat() / MAX_PREVIEW_PX
                decoder.setTargetSize(
                    (info.size.width / ratio).toInt().coerceAtLeast(1),
                    (info.size.height / ratio).toInt().coerceAtLeast(1),
                )
            }
            // A hardware bitmap cannot be written into a Notification's parcel.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        Log.status(TAG, "cannot decode preview for ${image.name}", e)
        null
    }

    private fun failedNotification(reason: String) = base(CHANNEL_TRANSFERS)
        .setContentTitle(appContext.getString(R.string.share_transfer_failed))
        .setContentText(reason)
        .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
        .setAutoCancel(true)
        .build()

    /**
     * A broadcast to [ShareNotificationReceiver] for Accept / Reject / Cancel.
     *
     * Both a distinct `requestCode` **and** a distinct [Intent.data] per (handle, action):
     * `Intent` equality ignores extras, so with `FLAG_UPDATE_CURRENT` two handles' Accept
     * intents would collapse onto one `PendingIntent` and deliver whichever handle was
     * registered last.
     */
    private fun sessionAction(action: String, handle: Long, notifId: Int): PendingIntent {
        val intent = Intent(appContext, ShareNotificationReceiver::class.java).apply {
            this.action = action
            data = "share://session/$handle/$action".toUri()
            putExtra(ShareTransferService.EXTRA_SESSION_HANDLE, handle)
            putExtra(ShareTransferService.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            requestCode(handle, action),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The system chooser, launched straight from the notification.
     *
     * `PendingIntent.getActivity` rather than `ExternalIntents.launch`, which calls
     * `startActivity` without `FLAG_ACTIVITY_NEW_TASK` and so cannot run from a service or
     * broadcast context.
     *
     * **Nothing may set `data` on the chooser intent.** `ACTION_CHOOSER` is resolved by the
     * framework's `ChooserActivity`, whose intent-filter declares no data at all, and a filter
     * with no data element does not match an intent that has a URI. Setting one made
     * `startActivity` throw `ActivityNotFoundException` inside the `PendingIntent`, which the
     * system only logs — so Share appeared to do nothing whatsoever.
     *
     * That leaves the `requestCode` carrying the whole burden of keeping two peers' Share
     * intents apart, because `filterEquals` ignores extras and every chooser intent is
     * otherwise identical. [notifId] is safe for that: it comes from a counter, not a hash.
     */
    private fun sharePendingIntent(uris: List<Uri>, mime: String, notifId: Int): PendingIntent {
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList(uris),
            )
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(
            send,
            appContext.getString(R.string.share_received_chooser),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            appContext,
            notifId * REQUEST_CODES_PER_NOTIF,
            chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun savePendingIntent(uris: List<Uri>, notifId: Int): PendingIntent {
        val intent = Intent(appContext, ShareSaveActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ShareSaveActivity.EXTRA_FILE_URIS, ArrayList(uris))
        }
        return PendingIntent.getActivity(
            appContext,
            notifId * REQUEST_CODES_PER_NOTIF + 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun requestCode(handle: Long, action: String): Int =
        (handle.hashCode() * HASH_MULTIPLIER + action.hashCode())

    /** Shared constants and helpers for ShareReceiveNotifier. */
    companion object {
        /**
         * Clear every per-transfer notification left over from a previous process.
         *
         * Posted notifications are the one thing that outlives process death: the sockets,
         * native handles and partial files do not, so a Request notification found at startup
         * points at a session that no longer exists and whose Accept can never work.
         */
        fun cancelStale(context: Context) {
            val nm = context.getSystemService<NotificationManager>() ?: return
            cancelStaleNotifications(nm)
        }

        // Broad catch is deliberate: activeNotifications throws SecurityException
        // plus undocumented RuntimeExceptions without the permission.
        @Suppress("TooGenericExceptionCaught")
        private fun cancelStaleNotifications(nm: NotificationManager) {
            try {
                nm.activeNotifications
                    .filter { it.notification.group == GROUP_KEY }
                    .forEach { nm.cancel(it.id) }
            } catch (e: Exception) {
                Log.status(TAG, "could not enumerate active notifications", e)
            }
        }
    }
}

