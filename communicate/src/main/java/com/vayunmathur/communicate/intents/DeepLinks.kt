package com.vayunmathur.communicate.intents

import android.content.Intent
import android.net.Uri
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.notifications.ConversationSpace

/**
 * What an incoming intent asked us to open.
 *
 * The app previously declared `sms:`/`smsto:`/`tel:` filters and then ignored the intent entirely, so every
 * launch landed on the thread list. This turns an intent into a destination.
 */
sealed interface DeepLink {
    /** Open (or start) a conversation with [address] on [line]. */
    data class Conversation(
        val address: String,
        val line: CommunicateLine,
        val body: String? = null,
        val remoteId: String? = null,
        val threadId: Long = -1L,
        val isGroup: Boolean = false,
        val groupTitle: String? = null,
        val subscriptionId: Int? = null,
        /** URIs shared into this conversation (Direct Share with a stream), as strings. */
        val sharedAttachmentUris: List<String> = emptyList(),
    ) : DeepLink

    /**
     * A share with no conversation chosen yet (Communicate picked as the regular
     * share target, not a Direct Share conversation). Held in [PendingShare] until
     * the user opens a thread from the list, which then seeds its composer.
     */
    data class SharePicker(
        val body: String? = null,
        val sharedAttachmentUris: List<String> = emptyList(),
    ) : DeepLink

    /** A group invite we can't join yet, but shouldn't silently swallow either. */
    data class UnsupportedGroupInvite(val line: CommunicateLine) : DeepLink
}

/**
 * Parses share/deep-link intents into a [DeepLink].
 *
 * Vendor links are matched by host so the app can be a target for `wa.me` and `signal.me` links, which is
 * what makes "message this number on WhatsApp/Signal" from a browser or another app land here. The line is
 * taken from the link, since the same phone number can exist on several.
 */
object DeepLinks {
    fun parse(intent: Intent?): DeepLink? {
        val intent = intent ?: return null
        // A tap on a conversation notification / shortcut carries the thread in extras, not a URI.
        // A Direct Share tap is the same shortcut intent plus the shared content, so
        // seed the composer from the share extras when they are present.
        fromConversationExtras(intent)?.let { conversation ->
            return conversation.copy(
                body = intent.getStringExtra(Intent.EXTRA_TEXT) ?: conversation.body,
                sharedAttachmentUris = sharedAttachmentUris(intent),
            )
        }
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val body = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uris = sharedAttachmentUris(intent)
            // Direct Share without the shortcut's own extras: the chooser only
            // guarantees EXTRA_SHORTCUT_ID (`<line>:<remoteId|threadId|address>`,
            // ConversationSpace.shortcutId), so rebuild the best conversation it names.
            intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
                ?.let { fromShortcutId(it, body, uris) }
                ?.let { return it }
            return DeepLink.SharePicker(body = body, sharedAttachmentUris = uris)
        }
        val data = intent.data ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO, Intent.ACTION_DIAL, Intent.ACTION_CALL ->
                fromUri(data, intent.getStringExtra(Intent.EXTRA_TEXT))
            else -> null
        }
    }

    /** Stream URIs carried by a SEND / SEND_MULTIPLE intent, as strings. */
    private fun sharedAttachmentUris(intent: Intent): List<String> = runCatching {
        when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.toString(),
            )
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.filterIsInstance<Uri>()
                    ?.map { it.toString() }
                    .orEmpty()
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** Inverse of `ConversationSpace.shortcutId` for a Direct Share tap. */
    private fun fromShortcutId(
        shortcutId: String,
        body: String?,
        uris: List<String>,
    ): DeepLink.Conversation? {
        val lineName = shortcutId.substringBefore(':', "")
        val key = shortcutId.substringAfter(':', "")
        if (lineName.isEmpty() || key.isEmpty()) return null
        val line = runCatching { CommunicateLine.valueOf(lineName) }.getOrNull() ?: return null
        val threadId = key.toLongOrNull()
        return DeepLink.Conversation(
            address = if (threadId != null) "" else key,
            line = line,
            body = body,
            remoteId = if (threadId != null) null else key,
            threadId = threadId ?: -1L,
            sharedAttachmentUris = uris,
        )
    }

    private fun fromConversationExtras(intent: Intent): DeepLink.Conversation? {
        val lineName = intent.getStringExtra(ConversationSpace.EXTRA_LINE) ?: return null
        val line = runCatching { CommunicateLine.valueOf(lineName) }.getOrNull() ?: return null
        return DeepLink.Conversation(
            address = intent.getStringExtra(ConversationSpace.EXTRA_ADDRESS).orEmpty(),
            line = line,
            remoteId = intent.getStringExtra(ConversationSpace.EXTRA_REMOTE_ID),
            threadId = intent.getLongExtra(ConversationSpace.EXTRA_THREAD_ID, -1L),
            isGroup = intent.getBooleanExtra(ConversationSpace.EXTRA_IS_GROUP, false),
            groupTitle = intent.getStringExtra(ConversationSpace.EXTRA_TITLE),
            subscriptionId = intent.getIntExtra(ConversationSpace.EXTRA_SUB_ID, -1).takeIf { it >= 0 },
        )
    }

    internal fun fromUri(uri: Uri, body: String? = null): DeepLink? = resolve(
        scheme = uri.scheme,
        host = uri.host,
        path = uri.path,
        fragment = uri.fragment,
        opaque = uri.schemeSpecificPart,
        query = { name -> runCatching { uri.getQueryParameter(name) }.getOrNull() },
        body = body,
    )

    /**
     * The parsing itself, over plain URI components.
     *
     * Split out from [Uri] so it is unit-testable: `android.net.Uri` is a stub on the JVM, and this is exactly
     * the logic worth testing since each vendor hides the number somewhere different.
     */
    internal fun resolve(
        scheme: String?,
        host: String?,
        path: String?,
        fragment: String?,
        opaque: String?,
        query: (String) -> String?,
        body: String? = null,
    ): DeepLink? {
        val scheme = scheme?.lowercase()
        val host = host?.lowercase()
        return when {
            // sms:/smsto:/mms: — the default-SMS-app entry point.
            scheme in SMS_SCHEMES || scheme == "tel" ->
                addressOf(opaque, path)?.let { DeepLink.Conversation(it, CommunicateLine.Sim, body) }

            // WhatsApp: wa.me/<number>, api.whatsapp.com/send?phone=, whatsapp://send?phone=
            host == "wa.me" || host == "api.whatsapp.com" || scheme == "whatsapp" ->
                whatsAppNumber(path, query)?.let {
                    DeepLink.Conversation(it, CommunicateLine.WhatsApp, query("text") ?: body)
                }
            host == "chat.whatsapp.com" -> DeepLink.UnsupportedGroupInvite(CommunicateLine.WhatsApp)

            // Signal: signal.me/#p/<e164>, sgnl://signal.me/#p/<e164>
            host == "signal.me" -> signalNumber(fragment)?.let {
                DeepLink.Conversation(it, CommunicateLine.Signal, body)
            }
            host == "signal.group" -> DeepLink.UnsupportedGroupInvite(CommunicateLine.Signal)
            else -> null
        }
    }

    /** `sms:+1555…?body=hi` keeps the number in the opaque part or the path depending on the sender. */
    private fun addressOf(opaque: String?, path: String?): String? {
        val raw = opaque ?: path
        return raw?.substringBefore('?')?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `wa.me/15551234567` puts it in the path; `api.whatsapp.com/send?phone=` in a query parameter. */
    private fun whatsAppNumber(path: String?, query: (String) -> String?): String? {
        query("phone")?.trim()?.takeIf { it.isNotEmpty() }?.let { return normalise(it) }
        val fromPath = path?.trim('/')?.takeIf { it.isNotEmpty() && it.any(Char::isDigit) }
        return fromPath?.let { normalise(it) }
    }

    /**
     * `https://signal.me/#p/+15551234567` — the number is in the fragment, not the path, so the path is empty
     * and the fragment has to be read directly.
     */
    private fun signalNumber(fragment: String?): String? {
        val value = fragment?.trim()?.removePrefix("p/")?.trim()
        return value?.takeIf { it.any(Char::isDigit) }?.let { normalise(it) }
    }

    /** Vendor links often omit the leading `+` even though the digits are international. */
    private fun normalise(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        return if (digits.startsWith("+") || digits.isEmpty()) digits else "+$digits"
    }

    private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
}
