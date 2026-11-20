package com.vayunmathur.communicate.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.library.util.ensureNotificationChannel

/**
 * Identifies one conversation across the three surfaces that make up Android's Conversation Space:
 * a long-lived dynamic shortcut, a `MessagingStyle` notification that references it, and a bubble.
 */
data class ConversationTarget(
    val line: CommunicateLine,
    val address: String,
    val remoteId: String? = null,
    val threadId: Long = -1L,
    val isGroup: Boolean = false,
    /** Group/conversation title, shown for group threads. */
    val title: String? = null,
    /** Display name of the other party (or the sender, for a group). */
    val personName: String,
    val subscriptionId: Int? = null,
)

/**
 * Promotes Communicate's message notifications into the system Conversation Space.
 *
 * A notification only reaches the conversations section (and becomes eligible for prioritisation and
 * bubbling) when it is a [NotificationCompat.MessagingStyle] notification whose `shortcutId` points at
 * a *long-lived* dynamic shortcut in the [ShortcutManagerCompat.getShortcuts] set, tagged with the
 * system conversations category. This centralises that wiring so every line (SIM, Google Voice,
 * WhatsApp, Signal) behaves identically.
 */
object ConversationSpace {
    // Extras describing a conversation, shared by the tap intent, the shortcut intent and the bubble.
    const val EXTRA_LINE = "convo_line"
    const val EXTRA_ADDRESS = "convo_address"
    const val EXTRA_REMOTE_ID = "convo_remote_id"
    const val EXTRA_THREAD_ID = "convo_thread_id"
    const val EXTRA_IS_GROUP = "convo_is_group"
    const val EXTRA_TITLE = "convo_title"
    const val EXTRA_SUB_ID = "convo_sub_id"

    /** Incoming-message channel for the SIM line (the vendor lines own their own channels). */
    const val SIM_CHANNEL_ID = "sms_messages_incoming"

    private const val CONVERSATION_CATEGORY = "com.google.android.category.CONVERSATIONS"

    // One notification per conversation: newer messages replace the previous one under the same tag,
    // which is what makes a thread accumulate in the shade rather than stacking N separate entries.
    private const val MESSAGE_NOTIFICATION_ID = 1

    /** Stable per-conversation id, unique across lines. */
    fun shortcutId(target: ConversationTarget): String {
        val key = target.remoteId
            ?: target.threadId.takeIf { it >= 0 }?.toString()
            ?: target.address
        return "${target.line.name}:$key"
    }

    /** Copy the conversation descriptor onto [intent] so the target screen can rebuild it. */
    fun putExtras(intent: Intent, target: ConversationTarget): Intent = intent.apply {
        putExtra(EXTRA_LINE, target.line.name)
        putExtra(EXTRA_ADDRESS, target.address)
        putExtra(EXTRA_REMOTE_ID, target.remoteId)
        putExtra(EXTRA_THREAD_ID, target.threadId)
        putExtra(EXTRA_IS_GROUP, target.isGroup)
        putExtra(EXTRA_TITLE, target.title)
        putExtra(EXTRA_SUB_ID, target.subscriptionId ?: -1)
    }

    /** Create (or refresh) an incoming-message channel that permits bubbles. */
    fun ensureIncomingChannel(context: Context, channelId: String, name: String, description: String?) {
        context.ensureNotificationChannel(
            id = channelId,
            name = name,
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = description,
        ) {
            setAllowBubbles(true)
        }
    }

    private fun person(context: Context, target: ConversationTarget): Person =
        Person.Builder()
            .setName(target.personName)
            .setKey(shortcutId(target))
            .setImportant(true)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()

    /** Publish/refresh the long-lived shortcut backing [target]; returns its id. */
    fun pushShortcut(context: Context, target: ConversationTarget): String {
        val id = shortcutId(target)
        val label = (target.title ?: target.personName).ifBlank { context.getString(R.string.app_name) }
        val intent = putExtras(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            target,
        )
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setLongLived(true)
            .setShortLabel(label)
            .setIntent(intent)
            .setPerson(person(context, target))
            .setCategories(setOf(CONVERSATION_CATEGORY))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        return id
    }

    /** Remove the shortcut for [target] (e.g. when a conversation is deleted). */
    fun removeShortcut(context: Context, target: ConversationTarget) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(shortcutId(target))) }
    }

    /**
     * Cancel the incoming-message notification for a conversation, matching the
     * tag/id [notifyIncoming] posted it under (`shortcutId` tag, [MESSAGE_NOTIFICATION_ID]).
     * Called when the conversation is opened: the tapped notification auto-cancels
     * itself, but opening from the list (or a second message replacing the first
     * under the same tag) leaves it in the shade until cancelled here.
     */
    fun cancelIncoming(context: Context, target: ConversationTarget) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(shortcutId(target), MESSAGE_NOTIFICATION_ID) }
    }

    /** [cancelIncoming] from the primitives a conversation screen already holds. */
    fun cancelIncoming(
        context: Context,
        line: CommunicateLine,
        address: String,
        remoteId: String? = null,
        threadId: Long = -1L,
    ) {
        cancelIncoming(
            context,
            ConversationTarget(
                line = line,
                address = address,
                remoteId = remoteId,
                threadId = threadId,
                personName = address,
            ),
        )
    }

    /**
     * Post a conversation-style notification for an inbound message, pushing the backing shortcut and
     * attaching bubble metadata first so the system can surface it in the conversations section.
     */
    fun notifyIncoming(
        context: Context,
        target: ConversationTarget,
        channelId: String,
        body: String,
        timestamp: Long,
        @DrawableRes smallIcon: Int,
    ) {
        val shortcutId = pushShortcut(context, target)
        val sender = person(context, target)
        val self = Person.Builder().setName(context.getString(R.string.you)).build()

        val messagingStyle = NotificationCompat.MessagingStyle(self)
            .setGroupConversation(target.isGroup)
            .setConversationTitle(target.title.takeIf { target.isGroup })
            .addMessage(body, timestamp, sender)

        val contentIntent = PendingIntent.getActivity(
            context,
            shortcutId.hashCode(),
            putExtras(
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                target,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val bubbleIntent = PendingIntent.getActivity(
            context,
            "bubble:$shortcutId".hashCode(),
            putExtras(
                Intent(context, BubbleActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
                target,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
            bubbleIntent,
            IconCompat.createWithResource(context, R.mipmap.ic_launcher),
        )
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(target.title ?: target.personName)
            .setContentText(body)
            .setStyle(messagingStyle)
            .setShortcutId(shortcutId)
            .setLocusId(LocusIdCompat(shortcutId))
            .addPerson(sender)
            .setContentIntent(contentIntent)
            .setBubbleMetadata(bubbleMetadata)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .extend(carExtender(target.title ?: target.personName, body, smallIcon))
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(shortcutId, MESSAGE_NOTIFICATION_ID, notification)
    }

    /**
     * Car head-unit extension: without a `CarAppExtender` the notification is
     * never shown on the car. Extends with title/text/small icon so the host
     * renders a HUN (high importance) or rail badge, and the tap intent
     * deep-links through the car app service. Max 2 car actions — none here;
     * reply lives in the car template via `ConversationCallback`.
     */
    private fun carExtender(
        title: String,
        text: String,
        @DrawableRes smallIcon: Int,
    ): androidx.car.app.notification.CarAppExtender =
        androidx.car.app.notification.CarAppExtender.Builder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .build()

    private const val BUBBLE_HEIGHT_DP = 600
}
