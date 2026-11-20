package com.vayunmathur.email.platform

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.email.MainActivity
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.library.util.ensureNotificationChannel

/** Posts and manages new-mail notifications and their channels. */
object EmailNotifications {

    private const val CHANNEL_PREFIX = "new_mail::"
    private const val HASH_PRIME = 31
    private const val SORT_KEY_WIDTH = 20
    private const val ACTION_ID_MULTIPLIER = 31
    private const val MARK_READ_OFFSET = 1
    private const val DELETE_OFFSET = 2
    private const val BODY_SNIPPET_LEN = 200

    /** Stable per-account channel id so each account gets its own OS toggle. */
    private fun channelId(accountEmail: String) = CHANNEL_PREFIX + accountEmail

    /**
     * Stable notification group key for one account. It is deliberately the
     * account email itself: every child for the account shares it, and the
     * single group-summary notification (below) joins the same group.
     */
    private fun groupKey(accountEmail: String) = accountEmail

    /**
     * Id of the one group-summary notification for [accountEmail]. Derived
     * from a summary-specific string, not from any message id, so it never
     * reuses a child's [notificationId] for the messages in the tests/callers
     * (a child id is `accountHash * 31 xor messageId`).
     */
    private fun summaryNotificationId(accountEmail: String): Int =
        "summary::${channelId(accountEmail)}".hashCode()

    /** Idempotent — creates a per-account notification channel. */
    fun ensureChannel(context: Context, accountEmail: String) {
        context.ensureNotificationChannel(
            channelId(accountEmail),
            accountEmail,
            NotificationManager.IMPORTANCE_DEFAULT,
            "New mail for $accountEmail",
        ) {
            setShowBadge(true)
        }
    }

    /**
     * Post one notification per [messages]. Tapping it launches MainActivity
     * with extras that navigate to the message thread (reusing the existing
     * intent-extra handling in `MainActivity.handleIntent`).
     *
     * Posted oldest-first so Android's shade — which sorts within a channel
     * by post-time, newest on top — renders newest email at the top of the
     * stack. The notification's displayed timestamp is also pinned to the
     * email's actual receive time via setWhen instead of the post wall-clock.
     */
    fun postForNewMessages(context: Context, accountEmail: String, messages: List<EmailMessage>) {
        if (messages.isEmpty()) return
        ensureChannel(context, accountEmail)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return

        val ordered = messages.sortedBy { it.dateMillis }
        for (msg in ordered) {
            postOne(nm, context, accountEmail, msg)
        }
        postSummary(nm, context, accountEmail, ordered)
    }

    /**
     * Cancel the notifications for [messages] (one per message, posted under
     * [notificationId]). Called when their thread is opened: the tapped
     * notification auto-cancels itself, but the rest of the thread's stay in
     * the shade until they are cancelled here.
     */
    fun cancelForMessages(context: Context, messages: List<EmailMessage>) {
        if (messages.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        for (msg in messages) {
            try {
                nm.cancel(notificationId(msg))
            } catch (_: SecurityException) {
                // Notifications revoked — nothing to cancel.
            }
        }
        // Child ids above are unchanged, so cancel-on-read keeps working.
        // Drop the summary only once its group has no children left;
        // otherwise it must stay to summarise the remaining unread mail.
        for (accountEmail in messages.map { it.accountEmail }.distinct()) {
            cancelSummaryIfNoChildren(context, accountEmail)
        }
    }

    /**
     * Cancel [accountEmail]'s group summary iff no grouped child remains.
     * Also called after a notification action (mark-read/delete) dismisses
     * a single child directly by id, so the summary never outlives its group.
     */
    fun cancelSummaryIfNoChildren(context: Context, accountEmail: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val hasChild = runCatching {
            manager.activeNotifications.any { sbn ->
                sbn.id != summaryNotificationId(accountEmail) &&
                    sbn.notification.group == groupKey(accountEmail)
            }
        }.getOrDefault(true) // On any doubt keep the summary.
        if (hasChild) return
        try {
            NotificationManagerCompat.from(context).cancel(summaryNotificationId(accountEmail))
        } catch (_: SecurityException) {
            // Notifications revoked — nothing to cancel.
        }
    }

    /** Cancel the notification for a single message (see [cancelForMessages]). */
    fun cancelForMessage(context: Context, msg: EmailMessage) =
        cancelForMessages(context, listOf(msg))

    /**
     * Post (or refresh) the single group-summary notification for an account.
     *
     * Lines/count are rebuilt from the account's currently active grouped
     * children when the system can report them, so successive sync batches
     * accumulate in the summary instead of the newest batch replacing the
     * unread picture; [ordered] is the fallback when they cannot be read.
     * Re-posting under [summaryNotificationId] keeps it one-per-account.
     */
    private fun postSummary(
        nm: NotificationManagerCompat,
        context: Context,
        accountEmail: String,
        ordered: List<EmailMessage>,
    ) {
        try {
            nm.notify(summaryNotificationId(accountEmail), buildSummary(context, accountEmail, ordered))
        } catch (_: SecurityException) {
            // Pre-permission grant on Android 13+ — silently drop.
        }
    }

    private fun buildSummary(
        context: Context,
        accountEmail: String,
        ordered: List<EmailMessage>,
    ): Notification {
        val activeChildren = runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.filter { sbn ->
                    sbn.id != summaryNotificationId(accountEmail) &&
                        sbn.notification.group == groupKey(accountEmail)
                }
                ?.sortedByDescending { it.notification.`when` }
                .orEmpty()
        }.getOrDefault(emptyList())

        val lines: List<String>
        val count: Int
        val latestWhen: Long
        if (activeChildren.isNotEmpty()) {
            count = activeChildren.size
            latestWhen = activeChildren.first().notification.`when`
            lines = activeChildren.map { sbn ->
                val title = sbn.notification.extras.getCharSequence("android.title")?.toString()
                val text = sbn.notification.extras.getCharSequence("android.text")?.toString()
                listOfNotNull(title?.takeIf { it.isNotBlank() }, text?.takeIf { it.isNotBlank() })
                    .joinToString(": ")
                    .ifBlank { accountEmail }
            }
        } else {
            count = ordered.size
            latestWhen = ordered.maxOfOrNull { it.dateMillis }?.takeIf { it > 0L }
                ?: System.currentTimeMillis()
            lines = ordered.asReversed().map { msg ->
                val sender = msg.from.substringBefore("<").trim().ifEmpty { msg.from }
                "$sender: ${msg.subject.ifBlank { "(no subject)" }}"
            }
        }

        val title = if (count == 1) "1 new message" else "$count new messages"
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText(accountEmail)
        for (line in lines) style.addLine(line)

        // Summary tap opens the app (account inbox, no specific thread).
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("accountEmail", accountEmail)
        }
        val pi = PendingIntent.getActivity(
            context,
            summaryNotificationId(accountEmail),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId(accountEmail))
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setContentTitle(title)
            .setContentText(accountEmail)
            .setSubText(accountEmail)
            .setWhen(latestWhen)
            .setShowWhen(true)
            .setNumber(count)
            .setStyle(style)
            .setGroup(groupKey(accountEmail))
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun postOne(
        nm: NotificationManagerCompat,
        context: Context,
        accountEmail: String,
        msg: EmailMessage,
    ) {
        val notif = buildNotification(context, accountEmail, msg)
        try {
            nm.notify(notificationId(msg), notif)
        } catch (_: SecurityException) {
            // Pre-permission grant on Android 13+ — silently drop.
        }
    }

    private fun buildNotification(context: Context, accountEmail: String, msg: EmailMessage): Notification {
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("accountEmail", msg.accountEmail)
            putExtra("threadId", msg.threadId ?: msg.id.toString())
        }
        // Per-message request code so each PendingIntent is unique.
        val pi = PendingIntent.getActivity(
            context,
            msg.id.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nId = notificationId(msg)
        fun actionPi(action: String, rc: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            rc,
            EmailNotificationActionReceiver.intent(context, action, msg.accountEmail, msg.folderName, msg.id, nId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, channelId(accountEmail))
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setContentTitle(msg.from.substringBefore("<").trim().ifEmpty { msg.from })
            .setContentText(msg.subject.ifBlank { "(no subject)" })
            .setSubText(accountEmail)
            .setWhen(if (msg.dateMillis > 0L) msg.dateMillis else System.currentTimeMillis())
            .setShowWhen(true)
            // Stable sort key (lexicographic) so the shade keeps emails in
            // chronological order even if posts arrive simultaneously.
            .setSortKey(sortKey(msg))
            .addAction(
                0,
                "Mark read",
                actionPi(
                    EmailNotificationActionReceiver.ACTION_MARK_READ,
                    msg.id.hashCode() * ACTION_ID_MULTIPLIER + MARK_READ_OFFSET,
                ),
            )
            .addAction(
                0,
                "Delete",
                actionPi(
                    EmailNotificationActionReceiver.ACTION_DELETE,
                    msg.id.hashCode() * ACTION_ID_MULTIPLIER + DELETE_OFFSET,
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(msg.subject.ifBlank { "(no subject)" })
                        msg.body?.takeIf { it.isNotBlank() }?.let { snippet ->
                            append("\n\n")
                            append(snippet.take(BODY_SNIPPET_LEN))
                        }
                    }
                )
            )
            .setGroup(groupKey(accountEmail))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    private fun notificationId(msg: EmailMessage): Int {
        return ((msg.accountEmail.hashCode().toLong() * HASH_PRIME) xor msg.id).toInt()
    }

    /**
     * Lexicographic sort key descending in time: newer emails sort BEFORE
     * older ones in the shade. Subtract dateMillis from Long.MAX_VALUE so a
     * larger timestamp maps to a smaller numeric string after zero-padding.
     */
    private fun sortKey(msg: EmailMessage): String {
        val t = if (msg.dateMillis > 0L) msg.dateMillis else System.currentTimeMillis()
        val inverted = Long.MAX_VALUE - t
        return inverted.toString().padStart(SORT_KEY_WIDTH, '0')
    }
}
