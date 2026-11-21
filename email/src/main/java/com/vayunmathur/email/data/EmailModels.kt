package com.vayunmathur.email.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.core.text.HtmlCompat
import kotlinx.serialization.Serializable
import com.vayunmathur.email.network.imap.MimeParser

@Serializable
@Entity(primaryKeys = ["accountEmail", "fullName"])
/** A mail folder belonging to an account, keyed by account and full folder name. */
data class EmailFolder(
    val accountEmail: String,
    val fullName: String,
    val name: String,
    val parentFullName: String? = null,
    val holdsMessages: Boolean = true,
    val delimiter: String = "/"
)

@Serializable
@Entity(
    primaryKeys = ["accountEmail", "folderName", "id"],
    indices = [
        Index(value = ["folderName", "dateMillis"]),
        Index(value = ["accountEmail", "folderName", "dateMillis"]),
        Index(value = ["threadId"]),
    ],
)
/** A stored email message keyed by account, folder and IMAP UID. */
data class EmailMessage(
    val accountEmail: String,
    val folderName: String,
    val id: Long, // IMAP UID
    val serverId: String? = null, // Message-ID header
    val threadId: String? = null, // Gmail Thread ID or custom grouping
    val subject: String,
    val from: String,
    val to: String? = null,
    val cc: String? = null,
    val date: String,
    /** Date in epoch-millis. Used for chronological ordering, especially in the
     *  cross-account unified inbox where IMAP UIDs aren't comparable. Defaults
     *  to 0 for rows persisted before this column existed; backfilled on app
     *  start via [EmailQueryDao.getRowsWithZeroDateMillis]. */
    val dateMillis: Long = 0,
    val body: String? = null,
    val isHtml: Boolean = false,
    val isRead: Boolean = false,
    val references: String? = null, // References/In-Reply-To for threading
    val hasAttachments: Boolean = false,
    val snoozedUntil: Long = 0, // 0 = not snoozed; else epoch millis to resurface
    val listUnsubscribe: String? = null, // raw List-Unsubscribe header, if present
    val listUnsubscribePost: String? = null, // raw List-Unsubscribe-Post header (RFC 8058), if present
    /**
     * Stored plain-text snippet of [body] (first [PEEK_LEN] chars), computed via
     * [previewText] on every insert (see [EmailMessageDao.insertMessages]). Lets list and
     * widget screens render a preview without loading the full [body] column.
     *
     * `@ColumnInfo(defaultValue = "")` is required so the schema generated from this
     * entity matches `MIGRATION_19_20`, which `ALTER TABLE`s this column in with a
     * SQL `DEFAULT ''`; without it Room throws "Migration didn't properly handle:
     * EmailMessage" on open. Rows persisted before this column existed default to
     * `""` and are backfilled on app start via [EmailQueryDao.getRowsWithEmptyPeek].
     */
    @ColumnInfo(defaultValue = "")
    val peekContent: String = "",
)

/** Max length of the stored [EmailMessage.peekContent] snippet — enough for any
 *  single-line list row, widget item or notification preview. */
const val PEEK_LEN = 200

/**
 * Lightweight projection of [EmailMessage] for list and widget screens: every
 * column those screens draw, but **not** the heavy [EmailMessage.body]. Populated
 * by the `…Preview` `@Query` variants in [EmailQueryDao], which read the stored
 * [EmailMessage.peekContent] instead of recomputing a preview from the body.
 */
data class EmailPreview(
    val accountEmail: String,
    val folderName: String,
    val id: Long,
    val threadId: String? = null,
    val subject: String,
    val from: String,
    val date: String,
    val dateMillis: Long = 0,
    val isRead: Boolean = false,
    val hasAttachments: Boolean = false,
    val snoozedUntil: Long = 0,
    val peekContent: String = "",
)

@Entity
/** A sender address the user has blocked. */
data class BlockedSender(
    @PrimaryKey val address: String,
)

/**
 * Tombstone for a locally-deleted message. Recorded when the user deletes a
 * message so that the sync worker / IMAP IDLE fetch skips its UID and never
 * re-inserts it (the server expunge can lag or fail, leaving the UID briefly
 * fetchable). Keyed by the same (account, folder, uid) tuple as EmailMessage.
 */
@Entity(primaryKeys = ["accountEmail", "folderName", "uid"])
data class DeletedUid(
    val accountEmail: String,
    val folderName: String,
    val uid: Long,
)

@Serializable
@Entity(primaryKeys = ["accountEmail", "messageId", "partId"])
/** A stored attachment part belonging to a message. */
data class Attachment(
    val accountEmail: String,
    val folderName: String,
    val messageId: Long, // IMAP UID
    val partId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val localUri: String? = null
)

@Serializable
@Entity
/** A configured mail account identified by its email address. */
data class EmailAccount(
    @PrimaryKey val email: String,
    /**
     * Legacy OAuth-token columns. Kept in the schema so old DB rows still
     * load, but no longer written: every account now uses an app password.
     */
    val accessToken: String = "",
    val refreshToken: String? = null,
    val expiresAt: Long = 0,
    /**
     * Preset identifier — `gmail`, `outlook`, `yahoo`, `icloud`, `fastmail`, or
     * `custom`. Used to (a) skip Gmail-only behaviour (virtual folder filtering)
     * for non-Gmail accounts, and (b) display the provider name in
     * account-picker UIs.
     *
     * `@ColumnInfo(defaultValue = ...)` is required so the schema generated
     * from this entity matches `MIGRATION_6_7`, which `ALTER TABLE`s these
     * columns in with a SQL default. Without it Room would throw
     * `IllegalStateException: Migration didn't properly handle: EmailAccount`
     * at startup for users upgrading from v6.
     */
    @ColumnInfo(defaultValue = "gmail")
    val provider: String = "gmail",
    /** IMAP hostname this account fetches from. Hard-coded to Gmail's pre-multi-provider migration. */
    @ColumnInfo(defaultValue = "imap.gmail.com")
    val imapHost: String = "imap.gmail.com",
    @ColumnInfo(defaultValue = "993")
    val imapPort: Int = 993,
    /** true = implicit SSL/TLS on connect, false = plaintext + STARTTLS upgrade. */
    @ColumnInfo(defaultValue = "1")
    val imapUseSsl: Boolean = true,
    @ColumnInfo(defaultValue = "smtp.gmail.com")
    val smtpHost: String = "smtp.gmail.com",
    @ColumnInfo(defaultValue = "465")
    val smtpPort: Int = 465,
    @ColumnInfo(defaultValue = "1")
    val smtpUseSsl: Boolean = true,
    /**
     * Optional login username for IMAP/SMTP authentication when it differs
     * from the email address (common with custom providers). When null or
     * blank, [email] is used for authentication.
     */
    @ColumnInfo(defaultValue = "")
    val username: String = "",
    /**
     * Auth scheme. Only `password` is supported now; the column is preserved
     * with its original default for legacy DB compatibility, but every newly
     * added account stores credentials as an encrypted app password.
     */
    @ColumnInfo(defaultValue = "oauth2")
    val authType: String = "password",
    /** AES-256-GCM ciphertext of the app password. */
    val passwordEncrypted: ByteArray? = null,
    /** GCM IV used to decrypt [passwordEncrypted]. */
    val passwordIv: ByteArray? = null,
    /** Plain-text signature appended to outgoing messages from this account. */
    @ColumnInfo(defaultValue = "")
    val signature: String = "",
    /**
     * Explicit opt-in to accept any TLS certificate for this account's
     * IMAP/SMTP hosts (self-hosted servers with self-signed certs).
     * Default false = platform trust + hostname verification for all hosts.
     */
    @ColumnInfo(defaultValue = "0")
    val allowInsecureCertificates: Boolean = false,
) {
    fun getColor(): Long = accountColor(email)
}

/** Material palette used to give each account a stable color, by email hash. */
val ACCOUNT_COLORS: List<Long> = listOf(
    0xFFF44336, // Red
    0xFFE91E63, // Pink
    0xFF9C27B0, // Purple
    0xFF673AB7, // Deep Purple
    0xFF3F51B5, // Indigo
    0xFF2196F3, // Blue
    0xFF03A9F4, // Light Blue
    0xFF00BCD4, // Cyan
    0xFF009688, // Teal
    0xFF4CAF50, // Green
    0xFF8BC34A, // Light Green
    0xFFCDDC39, // Lime
    0xFFFFEB3B, // Yellow
    0xFFFFC107, // Amber
    0xFFFF9800, // Orange
    0xFFFF5722, // Deep Orange
    0xFF795548, // Brown
    0xFF9E9E9E, // Grey
    0xFF607D8B  // Blue Grey
)

/** Stable color for [email], derived from its hash. */
fun accountColor(email: String): Long {
    val hash = email.hashCode()
    return ACCOUNT_COLORS[(hash and Int.MAX_VALUE) % ACCOUNT_COLORS.size]
}

/**
 * Content of `<script>`, `<style>`, `<head>` and `<title>` elements.
 *
 * [HtmlCompat.fromHtml] emits the *text* inside these rather than dropping
 * them, so a message whose body is a full HTML document renders its stylesheet
 * as the first hundred characters of the preview.
 */
private val HTML_NON_CONTENT = Regex(
    """<(script|style|head|title)\b[^>]*>.*?</\1\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private val HTML_DECLARATION = Regex("""<[!?][^>]*>""")
/** Tags common enough that their presence means the body is HTML whatever the flag says. */
private val LOOKS_LIKE_HTML = Regex(
    """<(html|body|head|div|table|p|br|span|a|img|font)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)
/**
 * Includes the non-breaking space `fromHtml` produces from `&nbsp;` and the
 * Unicode line/paragraph separators, none of which `\s` matches.
 */
private val WHITESPACE_RUN = Regex("""[\s\u00A0\u2028\u2029]+""")

/**
 * Characters that draw as an empty box, or as nothing at all.
 *
 * `fromHtml` returns a `Spanned` in which every `<img>` is a U+FFFC anchored to
 * an `ImageSpan`; `toString()` discards the span and leaves the placeholder
 * behind with nothing to stand for. U+FFFD is the decoder's marker for bytes it
 * could not make sense of. The zero-width characters are the invisible padding
 * marketing mail uses to pull its preheader around, and the C0/C1 controls come
 * through from mail that was never really text.
 *
 * Deliberately excludes tab, newline and the vertical whitespace controls -
 * those separate words, so deleting them would run the words together where
 * collapsing them to a space does not.
 */
private val UNRENDERABLE = Regex("[\\uFFFC\\uFFFD\\u200B-\\u200F\\uFEFF\\u0000-\\u0008\\u000E-\\u001F\\u007F]")

/**
 * Plain-text rendering of this message's body.
 *
 * Falls back to sniffing the markup when [EmailMessage.isHtml] is false, because
 * plenty of senders set `text/plain` on a body that is anything but.
 */
fun EmailMessage.plainTextBody(): String? {
    val raw = body ?: return null
    if (!isHtml && !LOOKS_LIKE_HTML.containsMatchIn(raw)) return raw.replace(UNRENDERABLE, "")
    val stripped = raw
        .replace(HTML_COMMENT, " ")
        .replace(HTML_NON_CONTENT, " ")
        .replace(HTML_DECLARATION, " ")
    return HtmlCompat.fromHtml(stripped, HtmlCompat.FROM_HTML_MODE_LEGACY)
        .toString()
        .replace(UNRENDERABLE, "")
}

/**
 * A single-line snippet of the body for list rows, widgets and notifications.
 *
 * Collapses whitespace before truncating: the block structure of an HTML mail
 * turns into leading blank lines, so [plainTextBody] truncated as-is is often
 * entirely empty.
 */
fun EmailMessage.previewText(maxChars: Int): String =
    (plainTextBody() ?: "").replace(WHITESPACE_RUN, " ").trim().take(maxChars)

/** Display name for a "Name <addr>" sender header, falling back to the address.
 * Implemented via own RFC2047 decode + simple parse to remove Jakarta dependency. */
fun senderDisplayName(from: String): String {
    if (from.isBlank()) return ""
    // Try to extract "Name <addr>" pattern
    val trimmed = from.trim()
    // Decode RFC2047 first
    val decoded = MimeParser.decodeHeader(trimmed)
    // Look for <...> format
    val angleMatch = Regex("""^(.*)<([^>]+)>$""").find(decoded.trim())
    if (angleMatch != null) {
        val namePart = angleMatch.groupValues[1].trim().trim('"').trim()
        val addr = angleMatch.groupValues[2].trim()
        if (namePart.isNotBlank()) return namePart
        return addr
    }
    // No angle brackets — could be bare address or just name
    if (decoded.contains("@")) return decoded.substringBefore("<").trim()
    return decoded
}

/**
 * A detected way to unsubscribe from a message. Produced by [detectUnsubscribe]
 * and acted on by the message-view UI.
 */
sealed interface UnsubscribeMethod {
    /** RFC 8058 one-click: HTTPS POST to [url] with body `List-Unsubscribe=One-Click`. */
    data class OneClickPost(val url: String) : UnsubscribeMethod
    /** Open an unsubscribe web page ([url]) in the browser. */
    data class OpenWeb(val url: String) : UnsubscribeMethod
    /** Compose an unsubscribe email to [address] using the in-app composer. */
    data class SendMail(val address: String) : UnsubscribeMethod
}

/** Words that signal an unsubscribe affordance; kept small to avoid false positives. */
private val UNSUBSCRIBE_KEYWORDS = listOf("unsubscribe", "opt out", "opt-out", "optout")

/**
 * Work out how (if at all) the user can unsubscribe from this message.
 *
 * Order of preference, per the email standards:
 *  1. RFC 8058 one-click POST — when `List-Unsubscribe-Post: List-Unsubscribe=One-Click`
 *     is present together with an https URL in `List-Unsubscribe`.
 *  2. An https/http URL from the `List-Unsubscribe` header (opened in the browser).
 *  3. A `mailto:` target from the `List-Unsubscribe` header (composed in-app).
 *  4. Fallback: a conservative scan of the body for an "unsubscribe" link.
 */
fun EmailMessage.detectUnsubscribe(): UnsubscribeMethod? {
    listUnsubscribe?.let { header ->
        val targets = parseListUnsubscribe(header)
        val webUrl = targets.firstOrNull { it.startsWith("http", ignoreCase = true) }
        val httpsUrl = targets.firstOrNull { it.startsWith("https://", ignoreCase = true) }
        val mailto = targets.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }

        val isOneClick = listUnsubscribePost?.contains("one-click", ignoreCase = true) == true
        if (isOneClick && httpsUrl != null) return UnsubscribeMethod.OneClickPost(httpsUrl)
        if (webUrl != null) return UnsubscribeMethod.OpenWeb(webUrl)
        if (mailto != null) {
            val address = mailto.removePrefix("mailto:").removePrefix("MAILTO:").substringBefore("?").trim()
            if (address.contains("@")) return UnsubscribeMethod.SendMail(address)
        }
    }
    return findUnsubscribeLinkInBody()?.let { UnsubscribeMethod.OpenWeb(it) }
}

/** Extract the angle-bracketed targets from a List-Unsubscribe header (RFC 2369). */
private fun parseListUnsubscribe(header: String): List<String> =
    Regex("<([^>]+)>").findAll(header).map { it.groupValues[1].trim() }.toList()
        .ifEmpty { header.split(",").map { it.trim() }.filter { it.isNotEmpty() } }

/**
 * Conservatively look for an unsubscribe link in the body. Only returns a link
 * when the word "unsubscribe" (or an "opt out" variant) appears in the link's
 * href or visible text, to avoid mistaking ordinary links for unsubscribe ones.
 */
private fun EmailMessage.findUnsubscribeLinkInBody(): String? {
    val content = body ?: return null
    if (isHtml) {
        val anchor = Regex(
            "<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (match in anchor.findAll(content)) {
            val href = match.groupValues[1].trim()
            if (!href.startsWith("http", ignoreCase = true)) continue
            val haystack = (href + " " + match.groupValues[2]).lowercase()
            if (UNSUBSCRIBE_KEYWORDS.any { haystack.contains(it) }) return href
        }
        return null
    }
    // Plain text: return an http(s) URL sitting on a line that mentions unsubscribe.
    for (line in content.lineSequence()) {
        val lower = line.lowercase()
        if (UNSUBSCRIBE_KEYWORDS.none { lower.contains(it) }) continue
        Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(line)?.value
            ?.trimEnd('.', ',', ')', '>', ']')
            ?.let { return it }
    }
    return null
}

/// Pending outgoing message stored locally until it is successfully sent by the
/// background sender. Attachments are copied to app-private storage at queue time
/// (the original `content://` URIs aren't reliably readable across process
/// restarts) and `attachmentLocalPaths` is a JSON-encoded `List<String>` of those
/// absolute file paths. [inlineImageJson] stores JSON array of {cid,path,mime,name}.
@Serializable
@Entity
/** A queued outgoing message awaiting SMTP delivery. */
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountEmail: String,
    val to: String,
    val cc: String? = null,
    val bcc: String? = null,
    val subject: String,
    val body: String,
    val attachmentLocalPaths: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val inlineImageJson: String = "[]",
    val inReplyTo: String? = null,
    val references: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long = 0,
    val scheduledAt: Long = 0,
    val isHtml: Boolean = false,
    /**
     * Stable Message-ID (bare value, no angle brackets) minted once when
     * this entry is created and reused on every retry build, so a send
     * that timed out after DATA does not deliver a second copy under a
     * fresh ID. Null only on rows queued before v24.
     */
    val messageId: String? = null
)

@Entity
/** A saved draft message awaiting further editing or sending. */
data class DraftEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountEmail: String,
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    @ColumnInfo(defaultValue = "[]")
    val inlineImageJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis(),
)
