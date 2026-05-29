package com.vayunmathur.email.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vayunmathur.email.EmailFolder
import com.vayunmathur.email.EmailMessage
import com.vayunmathur.email.EmailAccount
import com.vayunmathur.email.Attachment
import com.vayunmathur.email.OutboxEntry

@Database(
    entities = [
        EmailFolder::class,
        EmailMessage::class,
        EmailAccount::class,
        Attachment::class,
        OutboxEntry::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class EmailDatabase : RoomDatabase() {
    abstract fun emailDao(): EmailDao

    companion object {
        @Volatile
        private var instance: EmailDatabase? = null

        /**
         * v4 → v5: Adds the OutboxEntry table. Non-destructive — keeps accounts,
         * folders, messages, and attachments intact across the upgrade.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
        }

        /**
         * v5 → v6: Add `dateMillis` column for proper chronological ordering of
         * messages (the existing `date` is a `Date.toString()` string that
         * sorts lexically — wrong, especially in the unified inbox). Existing
         * rows get 0; backfilled at app start from the parsed `date` string.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE EmailMessage ADD COLUMN dateMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): EmailDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EmailDatabase::class.java,
                    "email-db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    // Last-resort: if a schema mismatch shows up that we don't have a
                    // migration for, wipe rather than crash the app.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
