package com.vayunmathur.email.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseMigrations
import com.vayunmathur.email.data.EmailFolder
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.OutboxEntry
import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.BlockedSender
import com.vayunmathur.email.data.DeletedUid
import com.vayunmathur.email.data.Attachment

@Database(
    entities = [
        EmailFolder::class,
        EmailMessage::class,
        EmailAccount::class,
        Attachment::class,
        OutboxEntry::class,
        DraftEntry::class,
        BlockedSender::class,
        DeletedUid::class,
        EmailMessageFts::class,
    ],
    version = 24,
    exportSchema = true,
)
/** Room database holding email folders, messages, accounts and outbox state. */
abstract class EmailDatabase : RoomDatabase() {
    abstract fun accountDao(): EmailAccountDao
    abstract fun messageDao(): EmailMessageDao
    abstract fun queryDao(): EmailQueryDao
    abstract fun outboxDao(): EmailOutboxDao

    /** Provides the singleton EmailDatabase instance and its migrations. */
    companion object : DatabaseMigrations {
        @Volatile
        private var instance: EmailDatabase? = null

        /**
         * v1 → v2 (reconstructed; see below): multi-account keys.
         *
         * History: v1 (f554d13d6, "email: more comprehensive inbox") had only
         * EmailFolder(fullName PK, name, parentFullName, delimiter) and
         * EmailMessage(id+folderName PK, subject, from, date, body, isHtml).
         * The next commit touching the DB, 742082c48 ("email: make loading
         * faster"), jumped straight to version = 3 — version 2 was never
         * committed or shipped, so no device carries a "v2 schema" to match.
         * This step therefore takes the first half of that 1→3 diff: both
         * tables are recreated with `accountEmail` in their primary keys and
         * EmailFolder gains `holdsMessages`. Primary keys cannot be altered,
         * so the tables are rebuilt and rows copied; v1 had no account
         * concept, so existing rows are stamped with the empty account,
         * which matches no account and is repopulated per-account on sync
         * (the 1→3 build of the day wiped these rows via destructive
         * fallback, so preserving them at all is strictly better).
         */
        private val MIGRATION_1_2 = Migration(1, 2) {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `EmailFolder_new` (
                    `accountEmail` TEXT NOT NULL,
                    `fullName` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `parentFullName` TEXT,
                    `holdsMessages` INTEGER NOT NULL,
                    `delimiter` TEXT NOT NULL,
                    PRIMARY KEY(`accountEmail`, `fullName`)
                )
                """.trimIndent()
            )
            it.execSQL(
                "INSERT INTO `EmailFolder_new` (`accountEmail`, `fullName`, `name`, `parentFullName`, `holdsMessages`, `delimiter`) " +
                    "SELECT '', `fullName`, `name`, `parentFullName`, 1, `delimiter` FROM `EmailFolder`"
            )
            it.execSQL("DROP TABLE IF EXISTS `EmailFolder`")
            it.execSQL("ALTER TABLE `EmailFolder_new` RENAME TO `EmailFolder`")
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `EmailMessage_new` (
                    `accountEmail` TEXT NOT NULL,
                    `folderName` TEXT NOT NULL,
                    `id` INTEGER NOT NULL,
                    `subject` TEXT NOT NULL,
                    `from` TEXT NOT NULL,
                    `date` TEXT NOT NULL,
                    `body` TEXT,
                    `isHtml` INTEGER NOT NULL,
                    PRIMARY KEY(`accountEmail`, `folderName`, `id`)
                )
                """.trimIndent()
            )
            it.execSQL(
                "INSERT INTO `EmailMessage_new` (`accountEmail`, `folderName`, `id`, `subject`, `from`, `date`, `body`, `isHtml`) " +
                    "SELECT '', `folderName`, `id`, `subject`, `from`, `date`, `body`, `isHtml` FROM `EmailMessage`"
            )
            it.execSQL("DROP TABLE IF EXISTS `EmailMessage`")
            it.execSQL("ALTER TABLE `EmailMessage_new` RENAME TO `EmailMessage`")
        }

        /**
         * v2 → v3 (reconstructed): the EmailAccount table, the second half
         * of 742082c48's 1→3 diff (EmailAccount(email PK, accessToken,
         * refreshToken, expiresAt)). Creates the table empty: a v1 install
         * held its single Gmail OAuth identity outside Room, and the tokens
         * are re-established by signing in again.
         */
        private val MIGRATION_2_3 = Migration(2, 3) {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `EmailAccount` (
                    `email` TEXT NOT NULL,
                    `accessToken` TEXT NOT NULL,
                    `refreshToken` TEXT,
                    `expiresAt` INTEGER NOT NULL,
                    PRIMARY KEY(`email`)
                )
                """.trimIndent()
            )
        }

        /**
         * v3 → v4 (reconstructed): a18f75606 ("email: sending emails", the
         * commit that raised version 3 → 4) added Attachment and the
         * EmailMessage columns serverId, threadId, to, cc, references and
         * hasAttachments; a3f7b10dd then added isRead without a version
         * bump, so every shipped v4 database carries it and MIGRATION_4_5
         * already assumes it. Attachment is created exactly as at v4.
         */
        private val MIGRATION_3_4 = Migration(3, 4) {
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN serverId TEXT")
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN threadId TEXT")
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN `to` TEXT")
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN cc TEXT")
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN `references` TEXT")
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN hasAttachments INTEGER NOT NULL DEFAULT 0")
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `Attachment` (
                    `accountEmail` TEXT NOT NULL,
                    `folderName` TEXT NOT NULL,
                    `messageId` INTEGER NOT NULL,
                    `partId` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `mimeType` TEXT NOT NULL,
                    `size` INTEGER NOT NULL,
                    `localUri` TEXT,
                    PRIMARY KEY(`accountEmail`, `messageId`, `partId`)
                )
                """.trimIndent()
            )
        }

        private val MIGRATION_4_5 = Migration(4, 5) {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `OutboxEntry` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `accountEmail` TEXT NOT NULL,
                    `to` TEXT NOT NULL,
                    `cc` TEXT,
                    `subject` TEXT NOT NULL,
                    `body` TEXT NOT NULL,
                    `attachmentLocalPaths` TEXT NOT NULL DEFAULT '[]',
                    `inReplyTo` TEXT,
                    `references` TEXT,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `lastError` TEXT,
                    `attemptCount` INTEGER NOT NULL DEFAULT 0,
                    `lastAttemptAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        private val MIGRATION_11_12 = Migration(11, 12) {
            it.execSQL("ALTER TABLE OutboxEntry ADD COLUMN scheduledAt INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_12_13 = Migration(12, 13) {
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN snoozedUntil INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_13_14 = Migration(13, 14) {
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN listUnsubscribe TEXT")
            it.execSQL("CREATE TABLE IF NOT EXISTS `BlockedSender` (`address` TEXT NOT NULL, PRIMARY KEY(`address`))")
        }

        private val MIGRATION_14_15 = Migration(14, 15) {
            it.execSQL("ALTER TABLE OutboxEntry ADD COLUMN isHtml INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_15_16 = Migration(15, 16) {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `DeletedUid` (
                    `accountEmail` TEXT NOT NULL,
                    `folderName` TEXT NOT NULL,
                    `uid` INTEGER NOT NULL,
                    PRIMARY KEY(`accountEmail`, `folderName`, `uid`)
                )
                """.trimIndent()
            )
        }

        private val MIGRATION_16_17 = Migration(16, 17) {
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN listUnsubscribePost TEXT")
        }

        private val MIGRATION_17_18 = Migration(17, 18) {
            it.execSQL("ALTER TABLE OutboxEntry ADD COLUMN inlineImageJson TEXT NOT NULL DEFAULT '[]'")
            it.execSQL("ALTER TABLE DraftEntry ADD COLUMN inlineImageJson TEXT NOT NULL DEFAULT '[]'")
        }

        /**
         * Office 365 / Exchange IMAP reports the inbox as "Inbox", while Gmail and
         * friends report "INBOX". RFC 3501 defines the name case-insensitively, but
         * SQLite TEXT comparison is not, so Outlook rows persisted as "Inbox" never
         * matched the `folderName = 'INBOX'` predicate behind the unified inbox.
         * Fold those legacy rows onto the canonical name. `OR REPLACE` collapses the
         * duplicates created when the IDLE push path (which hardcoded "INBOX") and
         * the sync worker (which used the server's casing) both stored the same UID.
         */
        private val MIGRATION_18_19 = Migration(18, 19) {
            // Matches a mixed-case spelling of the inbox itself ("Inbox", "inbox", ...).
            fun isInbox(column: String) = "$column <> 'INBOX' AND $column = 'INBOX' COLLATE NOCASE"

            // Matches a child of a mixed-case inbox, e.g. "Inbox/Receipts" or "Inbox.Receipts".
            fun isUnderInbox(column: String) =
                "length($column) > 5 AND substr($column, 1, 5) = 'INBOX' COLLATE NOCASE " +
                    "AND substr($column, 1, 5) <> 'INBOX' AND substr($column, 6, 1) IN ('/', '.')"

            // Rewrites just the leading "Inbox" segment, preserving the rest of the path.
            fun canonical(column: String) = "'INBOX' || substr($column, 6)"

            for ((table, column) in listOf(
                "EmailMessage" to "folderName",
                "DeletedUid" to "folderName",
                "Attachment" to "folderName",
                "EmailFolder" to "fullName",
            )) {
                it.execSQL("UPDATE OR REPLACE $table SET $column = 'INBOX' WHERE ${isInbox(column)}")
                it.execSQL("UPDATE OR REPLACE $table SET $column = ${canonical(column)} WHERE ${isUnderInbox(column)}")
            }
            it.execSQL("UPDATE EmailFolder SET parentFullName = 'INBOX' WHERE ${isInbox("parentFullName")}")
            it.execSQL(
                "UPDATE EmailFolder SET parentFullName = " +
                    "${canonical("parentFullName")} WHERE ${isUnderInbox("parentFullName")}",
            )
        }

        private val MIGRATION_5_6 = Migration(5, 6) {
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN dateMillis INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_6_7 = Migration(6, 7) {
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN provider TEXT NOT NULL DEFAULT 'gmail'")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN imapHost TEXT NOT NULL DEFAULT 'imap.gmail.com'")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN imapPort INTEGER NOT NULL DEFAULT 993")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN imapUseSsl INTEGER NOT NULL DEFAULT 1")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN smtpHost TEXT NOT NULL DEFAULT 'smtp.gmail.com'")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN smtpPort INTEGER NOT NULL DEFAULT 465")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN smtpUseSsl INTEGER NOT NULL DEFAULT 1")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN authType TEXT NOT NULL DEFAULT 'oauth2'")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN passwordEncrypted BLOB")
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN passwordIv BLOB")
        }

        private val MIGRATION_7_8 = Migration(7, 8) {
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN username TEXT NOT NULL DEFAULT ''")
            it.execSQL("UPDATE EmailAccount SET username = email")
        }

        private val MIGRATION_8_9 = Migration(8, 9) {
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN signature TEXT NOT NULL DEFAULT ''")
        }

        private val MIGRATION_9_10 = Migration(9, 10) {
            it.execSQL("ALTER TABLE OutboxEntry ADD COLUMN bcc TEXT")
        }

        private val MIGRATION_10_11 = Migration(10, 11) {
            it.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `DraftEntry` (
                    `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    `accountEmail` TEXT NOT NULL,
                    `to` TEXT NOT NULL,
                    `cc` TEXT NOT NULL,
                    `bcc` TEXT NOT NULL,
                    `subject` TEXT NOT NULL,
                    `body` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        /**
         * Adds the stored `peekContent` snippet column (see [EmailMessage.peekContent]).
         * Purely additive: existing rows get `''` and are backfilled from their body on
         * app start ([PeekContentBackfill]); the matching `@ColumnInfo(defaultValue = "")`
         * on the entity keeps the generated schema in sync so Room's post-migration
         * validation passes. No existing data is read or modified.
         */
        private val MIGRATION_19_20 = Migration(19, 20) {
            it.execSQL("ALTER TABLE EmailMessage ADD COLUMN peekContent TEXT NOT NULL DEFAULT ''")
        }

        /** Per-account TLS opt-in; existing accounts stay strict (0). */
        private val MIGRATION_20_21 = Migration(20, 21) {
            it.execSQL("ALTER TABLE EmailAccount ADD COLUMN allowInsecureCertificates INTEGER NOT NULL DEFAULT 0")
        }

        /**
         * Restores full-text search: an FTS4 virtual table over subject,
         * sender and body, backed by [EmailMessage] as its content table
         * (unicode61, diacritics removed). Room generates and maintains the
         * `room_fts_content_sync_*` triggers itself (fresh installs get them
         * from the generated open delegate; migrated installs get them from
         * its post-migrate step), so this migration only creates the index
         * and rebuilds it from the messages already on disk. The SQL mirrors
         * the generated schema exactly so post-migration validation passes.
         */
        private val MIGRATION_21_22 = Migration(21, 22) {
            it.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `EmailMessageFts` USING FTS4(" +
                    "`subject` TEXT NOT NULL, `from` TEXT NOT NULL, `body` TEXT, " +
                    "tokenize=unicode61 `remove_diacritics=1`, content=`EmailMessage`)",
            )
            it.execSQL("INSERT INTO `EmailMessageFts`(`EmailMessageFts`) VALUES('rebuild')")
        }

        /** v22 → v23: indices for folder/unified/thread reads ordered by dateMillis. */
        private val MIGRATION_22_23 = Migration(22, 23) {
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_EmailMessage_folderName_dateMillis` " +
                    "ON `EmailMessage` (`folderName`, `dateMillis`)",
            )
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_EmailMessage_accountEmail_folderName_dateMillis` " +
                    "ON `EmailMessage` (`accountEmail`, `folderName`, `dateMillis`)",
            )
            it.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_EmailMessage_threadId` ON `EmailMessage` (`threadId`)",
            )
        }

        /**
         * v23 → v24: stable Message-ID for outbox entries. Minted once at
         * enqueue time and reused on every retry, so an SMTP timeout after
         * DATA cannot deliver a duplicate under a regenerated ID. Existing
         * rows stay null; the worker mints and persists one on first retry.
         */
        private val MIGRATION_23_24 = Migration(23, 24) {
            it.execSQL("ALTER TABLE OutboxEntry ADD COLUMN messageId TEXT")
        }

        /**
         * Every migration for this database, registered with the Room builder via the
         * [DatabaseMigrations] reflection hook in `openRoomDatabase`. Order is
         * irrelevant to Room; kept numeric here for readability.
         */
        override val migrations: List<Migration> = listOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
        )

        fun getInstance(context: Context): EmailDatabase =
            EmailRepository.get(context).getDatabase()
    }
}
