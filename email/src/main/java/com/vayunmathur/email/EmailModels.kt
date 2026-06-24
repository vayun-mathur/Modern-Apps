package com.vayunmathur.email

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(primaryKeys = ["accountEmail", "fullName"])
data class EmailFolder(
    val accountEmail: String,
    val fullName: String,
    val name: String,
    val parentFullName: String? = null,
    val holdsMessages: Boolean = true,
    val delimiter: String = "/"
)

@Serializable
@Entity(primaryKeys = ["accountEmail", "folderName", "id"])
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
     *  start via [EmailDao.getRowsWithZeroDateMillis]. */
    val dateMillis: Long = 0,
    val body: String? = null,
    val isHtml: Boolean = false,
    val isRead: Boolean = false,
    val references: String? = null, // References/In-Reply-To for threading
    val hasAttachments: Boolean = false
)

@Serializable
@Entity(primaryKeys = ["accountEmail", "messageId", "partId"])
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
) {
    fun getColor(): Long {
        val colors = listOf(
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
        val hash = email.hashCode()
        return colors[(hash and Int.MAX_VALUE) % colors.size]
    }
}

/// Pending outgoing message stored locally until it is successfully sent by the
/// background sender. Attachments are copied to app-private storage at queue time
/// (the original `content://` URIs aren't reliably readable across process
/// restarts) and `attachmentLocalPaths` is a JSON-encoded `List<String>` of those
/// absolute file paths.
@Serializable
@Entity
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountEmail: String,
    val to: String,
    val cc: String? = null,
    val bcc: String? = null,
    val subject: String,
    val body: String,
    val attachmentLocalPaths: String = "[]",
    val inReplyTo: String? = null,
    val references: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long = 0
)
