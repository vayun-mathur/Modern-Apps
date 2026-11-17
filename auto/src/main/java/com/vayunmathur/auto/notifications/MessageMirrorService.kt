package com.vayunmathur.auto.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.auto.platform.MessagingPrefs
import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread

/**
 * Mirrors phone message notifications onto the projection session's ch14 owner.
 *
 * Posts only `CATEGORY_MESSAGE` notifications (SMS and chat apps tag theirs;
 * everything else is skipped) that carry non-blank text, and only while the
 * user has both granted the system listener binding and flipped the in-app
 * consent switch: either gate off means fail-closed silence, never a send.
 * The system binds this service itself once the user enables MA Auto in the
 * notification-access settings; there is nothing to start or stop.
 */
class MessageMirrorService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!MessagingPrefs.isMirroringConsented(this)) return
        val notification = sbn.notification
        if (notification.category != Notification.CATEGORY_MESSAGE) return
        val (thread, message) = extract(sbn) ?: return
        MessageMirrorBus.post(thread, message, replyRoute(sbn))
        activeCount.incrementAndGet()
        Log.dev(TAG, "mirrored message for thread ${thread.threadId}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Dismissals on the phone side stay local: without a per-thread read
        // store there is nothing truthful to tell the head unit.
        activeCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    /**
     * Pulls a ch14 snapshot out of a message notification, or null when it
     * carries nothing worth sending: no `MessagingStyle`, or a style with no
     * messages, or a latest message with blank text.
     */
    internal fun extract(sbn: StatusBarNotification): Pair<MessagingThread, MessagingMessage>? {
        val messages = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)
            ?.messages
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val latest = messages.lastOrNull { !it.text.isNullOrBlank() } ?: return null
        val threadId = conversationKey(sbn)
        val thread = MessagingThread.newBuilder()
            .setThreadId(threadId)
            .setTitle(conversationTitle(sbn, latest))
            .setSender(latest.person?.name?.toString().orEmpty())
            .setLastMessageMs(latest.timestamp)
            .build()
        val message = MessagingMessage.newBuilder()
            .setThreadId(threadId)
            .setMessageId(messageKey(sbn, latest.timestamp))
            .setSender(latest.person?.name?.toString().orEmpty())
            .setBody(latest.text.toString())
            .setTimestampMs(latest.timestamp)
            .build()
        return thread to message
    }

    /**
     * Stable per-conversation key: the notification's conversation id when the
     * app shortcuts one (people-tagged messages), else the posting package plus
     * tag plus id. Stable across reposts of the same thread, distinct across
     * apps and threads -- which is what ch14 groups by.
     */
    internal fun conversationKey(sbn: StatusBarNotification): String {
        val shortcut = sbn.notification.shortcutId
        if (!shortcut.isNullOrEmpty()) return "${sbn.packageName}:$shortcut"
        return "${sbn.packageName}:${sbn.tag}:${sbn.id}"
    }

    private fun conversationTitle(
        sbn: StatusBarNotification,
        latest: NotificationCompat.MessagingStyle.Message,
    ): String {
        val styleTitle = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)
            ?.conversationTitle
        if (!styleTitle.isNullOrBlank()) return styleTitle.toString()
        val sender = latest.person?.name?.toString()
        if (!sender.isNullOrBlank()) return sender
        return sbn.packageName
    }

    private fun messageKey(sbn: StatusBarNotification, timestamp: Long): String =
        "${sbn.id}:$timestamp"

    /**
     * Pulls the reply/mark-read route out of a message notification, or null
     * when the app offers no reply action.
     *
     * Only the direct-action shape is read: a notification action carrying a
     * `RemoteInput` (what modern message apps post with `MessagingStyle`).
     * The legacy `CarExtender` unread-conversation shape is deliberately not
     * read -- androidx.core removed its accessor, and every current message
     * app uses direct actions. An action without a reply intent is no route.
     */
    private fun replyRoute(sbn: StatusBarNotification): MessageReplyRoute? {
        val notification = sbn.notification
        val threadId = conversationKey(sbn)
        for (action in notification.actions.orEmpty()) {
            val remoteInputs = action.remoteInputs
            if (!remoteInputs.isNullOrEmpty() && action.actionIntent != null) {
                return MessageReplyRoute(
                    threadId = threadId,
                    replyIntent = action.actionIntent,
                    remoteInput = remoteInputs.first(),
                    readIntent = null,
                )
            }
        }
        return null
    }

    /** Constants for [MessageMirrorService]. */
    companion object {
        const val TAG = "MaAuto.MsgMirror"

        /**
         * Live message notifications currently held by the bound listener.
         * Incremented on post, decremented on remove (floored at zero); the
         * rail status badge reads this for its count. Zero before the first
         * bind, which keeps the badge gone like the layout default.
         */
        private val activeCount = java.util.concurrent.atomic.AtomicInteger(0)

        /** See [activeCount]; fail-closed zero when the listener never bound. */
        @JvmStatic
        fun activeMessageCount(): Int = activeCount.get().coerceAtLeast(0)
    }
}
