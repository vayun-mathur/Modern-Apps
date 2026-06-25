package com.vayunmathur.email.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.vayunmathur.email.EmailFolder
import com.vayunmathur.email.EmailMessage
import com.vayunmathur.email.EmailAccount
import com.vayunmathur.email.OutboxEntry
import com.vayunmathur.email.DraftEntry
import com.vayunmathur.email.Attachment

@Database(
    entities = [
        EmailFolder::class,
        EmailMessage::class,
        EmailAccount::class,
        Attachment::class,
        OutboxEntry::class,
        DraftEntry::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class EmailDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao

    companion object {
        @Volatile
        private var instance: EmailDatabase? = null

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

        fun getInstance(context: Context): EmailDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EmailDatabase::class.java,
                    "email-db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build().also { instance = it }
            }
        }
    }
}
