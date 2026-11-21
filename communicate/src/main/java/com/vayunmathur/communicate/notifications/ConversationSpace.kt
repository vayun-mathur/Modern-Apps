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

    /**
     * Direct Share category. Must match the `<category>` of the `<share-target>` in
     * `res/xml/shortcuts.xml`: a dynamic shortcut only appears in the system share
     * sheet's direct-share row when it carries this category, and tapping it launches
     * `MainActivity` (the share target) with the share intent.
     */
    const val SHARE_TARGET_CATEGORY = "com.vayunmathur.communicate.category.DIRECT_SHARE"

    /** How many top conversations are published as Direct Share targets. */
    const val MAX_SHARE_TARGETS = 4

    // One notification per conversation, under the same tag/id: re-posting it
    // updates the shade entry in place rather than stacking N entries. Since
    // this change the re-post carries every still-unread message in its
    // MessagingStyle (recovered from the previous posting, see notifyIncoming),
    // so the entry accumulates messages instead of showing only the newest.
    // No cross-conversation group summary: conversations already live in the
    // system's dedicated conversations section keyed by shortcut/bubble, and
    // each line owns a different channel — a single summary would have to pick
    // one line's channel/shortcut and would fight that ranking, so grouping
    // here is per-conversation only.
    private const val MESSAGE_NOTIFICATION_ID = 1

    /** Cap on messages retained in one conversation's [NotificationCompat.MessagingStyle]. */
    private const val MAX_MESSAGING_STYLE_MESSAGES = 25

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
    fun pushShortcut(
        context: Context,
        target: ConversationTarget,
        shareTarget: Boolean = false,
        rank: Int = 0,
    ): String {
        val id = shortcutId(target)
        val label = (target.title ?: target.personName).ifBlank { context.getString(R.string.app_name) }
        val intent = putExtras(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            target,
        )
        val categories = if (shareTarget) {
            setOf(CONVERSATION_CATEGORY, SHARE_TARGET_CATEGORY)
        } else {
            setOf(CONVERSATION_CATEGORY)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setLongLived(true)
            .setShortLabel(label)
            .setIntent(intent)
            .setPerson(person(context, target))
            .setCategories(categories)
            .setLocusId(LocusIdCompat(id))
            .setRank(rank)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        return id
    }

    /**
     * Publish the top [MAX_SHARE_TARGETS] conversations (callers pass them newest-first,
     * as `loadSmsThreadsMerged` returns them) as Direct Share targets, reusing the same
     * shortcut ids / locus ids as the conversation shortcuts so the share sheet, the
     * notifications and the bubbles all name the same conversation.
     *
     * Refreshed whenever the conversation list reloads. A conversation that falls out of
     * the top four is demoted back to a plain conversation shortcut (kept, so its
     * notification/bubble keep working); one that is gone entirely is removed.
     */
    fun publishShareTargets(context: Context, targets: List<ConversationTarget>) {
        val top = targets.distinctBy { shortcutId(it) }.take(MAX_SHARE_TARGETS)
        top.forEachIndexed { index, target ->
            pushShortcut(context, target, shareTarget = true, rank = index)
        }
        val topIds = top.mapTo(HashSet()) { shortcutId(it) }
        runCatching {
            val byId = targets.associateBy { shortcutId(it) }
            ShortcutManagerCompat.getDynamicShortcuts(context)
                .filter { shortcut ->
                    shortcut.categories?.contains(SHARE_TARGET_CATEGORY) == true &&
                        shortcut.id !in topIds
                }
                .forEach { stale ->
                    val target = byId[stale.id]
                    if (target != null) {
                        pushShortcut(context, target, shareTarget = false)
                    } else {
                        ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(stale.id))
                    }
                }
        }
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
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // Accumulate unread messages: recover the MessagingStyle from the
        // notification currently posted under this conversation's tag/id and
        // carry its messages forward. Reading the live notification (rather
        // than an in-memory list) survives process death, and cancelIncoming
        // removing that notification is exactly what resets the accumulation
        // once the conversation is read — no extra state to keep in sync.
        val previous = runCatching {
            nm.activeNotifications
                .firstOrNull { it.tag == shortcutId && it.id == MESSAGE_NOTIFICATION_ID }
                ?.notification
                ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
                ?.messages
                .orEmpty()
        }.getOrDefault(emptyList())

        val messagingStyle = NotificationCompat.MessagingStyle(self)
            .setGroupConversation(target.isGroup)
            .setConversationTitle(target.title.takeIf { target.isGroup })
        val retained = previous.takeLast(MAX_MESSAGING_STYLE_MESSAGES - 1)
        for (message in retained) {
            val text = message.text ?: continue
            messagingStyle.addMessage(text, message.timestamp, message.person)
        }
        // An exact re-delivery of the newest message (sync retry) must not
        // duplicate it; anything else appends, never replaces.
        val alreadyPresent = retained.any {
            it.text?.toString() == body && it.timestamp == timestamp
        }
        if (!alreadyPresent) {
            messagingStyle.addMessage(body, timestamp, sender)
        }

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
