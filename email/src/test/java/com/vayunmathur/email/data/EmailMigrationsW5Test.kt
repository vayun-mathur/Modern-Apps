package com.vayunmathur.email.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** W5: the full EmailDatabase migration chain over v1- and v4-shaped databases. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class EmailMigrationsW5Test {

    /**
     * The v1 schema exactly as f554d13d6 shipped it: EmailFolder keyed by
     * fullName alone and EmailMessage keyed by (id, folderName), no account
     * concept anywhere.
     */
    private fun openV1(): SQLiteConnection {
        val conn = AndroidSQLiteDriver().open(":memory:")
        conn.execSQL(
            "CREATE TABLE EmailFolder (`fullName` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`parentFullName` TEXT, `delimiter` TEXT NOT NULL, PRIMARY KEY(`fullName`))"
        )
        conn.execSQL(
            "CREATE TABLE EmailMessage (`id` INTEGER NOT NULL, `folderName` TEXT NOT NULL, " +
                "`subject` TEXT NOT NULL, `from` TEXT NOT NULL, `date` TEXT NOT NULL, `body` TEXT, " +
                "`isHtml` INTEGER NOT NULL, PRIMARY KEY(`id`, `folderName`))"
        )
        conn.execSQL("INSERT INTO EmailFolder (fullName, name, delimiter) VALUES ('INBOX', 'Inbox', '/')")
        conn.execSQL(
            "INSERT INTO EmailMessage (id, folderName, subject, `from`, date, body, isHtml) " +
                "VALUES (7, 'INBOX', 'Quarterly report', 'boss@work.com', '2026-01-01', 'numbers', 0)"
        )
        return conn
    }

    private fun openV4(): SQLiteConnection {
        val conn = AndroidSQLiteDriver().open(":memory:")
        conn.execSQL("CREATE TABLE EmailMessage (accountEmail TEXT NOT NULL, folderName TEXT NOT NULL, id INTEGER NOT NULL, threadId TEXT, subject TEXT, `from` TEXT, body TEXT)")
        conn.execSQL("CREATE TABLE EmailAccount (email TEXT NOT NULL PRIMARY KEY)")
        conn.execSQL("CREATE TABLE EmailFolder (accountEmail TEXT NOT NULL, fullName TEXT NOT NULL, parentFullName TEXT)")
        conn.execSQL("CREATE TABLE Attachment (accountEmail TEXT NOT NULL, folderName TEXT NOT NULL, messageId INTEGER NOT NULL)")
        conn.execSQL("INSERT INTO EmailAccount (email) VALUES ('me@x.com')")
        return conn
    }

    private fun columns(conn: SQLiteConnection, table: String): Set<String> {
        val out = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info(`$table`)").use {
            while (it.step()) out += it.getText(1)
        }
        return out
    }

    private fun tableExists(conn: SQLiteConnection, table: String): Boolean =
        conn.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?").use {
            it.bindText(1, table)
            it.step()
        }

    private fun strings(conn: SQLiteConnection, sql: String): List<String> =
        conn.prepare(sql).use { stmt ->
            val out = mutableListOf<String>()
            while (stmt.step()) out += stmt.getText(0)
            out
        }

    private fun migrate(conn: SQLiteConnection, from: Int, until: Int = Int.MAX_VALUE) = runBlocking {
        EmailDatabase.migrations
            .filter { it.startVersion >= from && it.startVersion < until }
            .sortedBy { it.startVersion }
            .forEach { it.migrate(conn) }
    }

    @Test
    fun migrationChainIsCompleteAndOrdered() {
        assertEquals(
            (1..23).map { it to it + 1 },
            EmailDatabase.migrations.map { it.startVersion to it.endVersion }.sortedBy { it.first },
        )
    }

    @Test
    fun v1DatabaseReachesV4ShapeThroughReconstructedMigrations() {
        val conn = openV1()
        migrate(conn, 1, until = 4)
        // v3 half: account table exists, folder/message keys carry the account.
        assertTrue(tableExists(conn, "EmailAccount"))
        assertTrue(columns(conn, "EmailFolder").containsAll(setOf("accountEmail", "holdsMessages")))
        assertTrue(columns(conn, "EmailMessage").contains("accountEmail"))
        // v4 half (a18f75606 + a3f7b10dd's unversioned isRead): threading
        // columns and Attachment, but nothing the 4->... chain adds later.
        assertTrue(
            columns(conn, "EmailMessage").containsAll(
                setOf("serverId", "threadId", "to", "cc", "references", "hasAttachments", "isRead")
            ),
            columns(conn, "EmailMessage").toString(),
        )
        assertTrue(tableExists(conn, "Attachment"))
        assertTrue(!columns(conn, "EmailMessage").contains("dateMillis"))
        // Rows survive the primary-key rebuild, stamped with the empty account.
        assertEquals(listOf("INBOX"), strings(conn, "SELECT fullName FROM EmailFolder"))
        assertEquals(listOf("Quarterly report"), strings(conn, "SELECT subject FROM EmailMessage"))
        assertEquals(listOf(""), strings(conn, "SELECT accountEmail FROM EmailMessage"))
        conn.close()
    }

    @Test
    fun v1DatabaseUpgradesAllTheWayToV24Shape() {
        val conn = openV1()
        migrate(conn, 1)
        assertTrue(tableExists(conn, "OutboxEntry"))
        assertTrue(tableExists(conn, "DraftEntry"))
        assertTrue(tableExists(conn, "BlockedSender"))
        assertTrue(tableExists(conn, "DeletedUid"))
        assertTrue(tableExists(conn, "EmailMessageFts"))
        assertTrue(tableExists(conn, "Attachment"))
        assertTrue(
            columns(conn, "EmailMessage").containsAll(
                setOf("serverId", "dateMillis", "snoozedUntil", "listUnsubscribe", "peekContent")
            ),
            columns(conn, "EmailMessage").toString(),
        )
        assertTrue(
            columns(conn, "EmailAccount").containsAll(setOf("provider", "username", "allowInsecureCertificates")),
            columns(conn, "EmailAccount").toString(),
        )
        assertEquals(listOf("Quarterly report"), strings(conn, "SELECT subject FROM EmailMessage"))
        conn.close()
    }

    @Test
    fun migration21To22CreatesFtsIndexOverExistingMessages() {
        val conn = openV4()
        migrate(conn, 4, until = 21)
        conn.execSQL(
            "INSERT INTO EmailMessage (accountEmail, folderName, id, subject, `from`, body) " +
                "VALUES ('me@x.com', 'INBOX', 1, 'Quarterly report', 'boss@work.com', 'numbers inside')",
        )
        conn.execSQL(
            "INSERT INTO EmailMessage (accountEmail, folderName, id, subject, `from`, body) " +
                "VALUES ('me@x.com', 'INBOX', 2, 'Hello', 'friend@x.com', 'dinner plans')",
        )
        migrate(conn, 21)
        assertTrue(tableExists(conn, "EmailMessageFts"))
        // The rebuild indexed rows that existed before the migration.
        assertEquals(
            listOf("1"),
            strings(conn, "SELECT docid FROM EmailMessageFts WHERE EmailMessageFts MATCH '\"quarter*\"'"),
        )
        assertEquals(
            listOf("2"),
            strings(conn, "SELECT docid FROM EmailMessageFts WHERE EmailMessageFts MATCH '\"dinner*\"'"),
        )
        conn.close()
    }

    @Test
    fun v4DatabaseUpgradesToV24Shape() {
        val conn = openV4()
        migrate(conn, 4)
        assertTrue(tableExists(conn, "OutboxEntry"))
        assertTrue(tableExists(conn, "DraftEntry"))
        assertTrue(tableExists(conn, "BlockedSender"))
        assertTrue(tableExists(conn, "DeletedUid"))
        assertTrue(tableExists(conn, "EmailMessageFts"))
        assertTrue(columns(conn, "EmailMessage").containsAll(setOf("dateMillis", "snoozedUntil", "listUnsubscribe", "listUnsubscribePost", "peekContent")), columns(conn, "EmailMessage").toString())
        assertTrue(columns(conn, "EmailAccount").containsAll(setOf("provider", "imapHost", "smtpHost", "authType", "username", "signature", "allowInsecureCertificates")), columns(conn, "EmailAccount").toString())
        assertTrue(columns(conn, "OutboxEntry").containsAll(setOf("scheduledAt", "isHtml", "bcc", "inlineImageJson", "messageId")), columns(conn, "OutboxEntry").toString())
        assertTrue(columns(conn, "DraftEntry").contains("inlineImageJson"))
        // The 6→7 backfill copies email into the new username column.
        assertEquals(listOf("me@x.com"), strings(conn, "SELECT username FROM EmailAccount"))
        conn.close()
    }

    @Test
    fun migration18To19CanonicalizesInboxCasing() {
        val conn = openV4()
        migrate(conn, 4, until = 18)
        conn.execSQL("INSERT INTO EmailMessage (accountEmail, folderName, id) VALUES ('me@x.com', 'Inbox', 1)")
        conn.execSQL("INSERT INTO EmailMessage (accountEmail, folderName, id) VALUES ('me@x.com', 'inbox/Receipts', 2)")
        conn.execSQL("INSERT INTO EmailMessage (accountEmail, folderName, id) VALUES ('me@x.com', 'Sent', 3)")
        conn.execSQL("INSERT INTO EmailFolder (accountEmail, fullName, parentFullName) VALUES ('me@x.com', 'Inbox', NULL)")
        conn.execSQL("INSERT INTO EmailFolder (accountEmail, fullName, parentFullName) VALUES ('me@x.com', 'Inbox.Receipts', 'Inbox')")
        conn.execSQL("INSERT INTO Attachment (accountEmail, folderName, messageId) VALUES ('me@x.com', 'INBOX', 1)")
        conn.execSQL("INSERT INTO DeletedUid (accountEmail, folderName, uid) VALUES ('me@x.com', 'inbox', 9)")

        migrate(conn, 18)
        assertEquals(
            listOf("INBOX", "INBOX/Receipts", "Sent"),
            strings(conn, "SELECT folderName FROM EmailMessage ORDER BY id"),
        )
        assertEquals(
            listOf("INBOX", "INBOX.Receipts"),
            strings(conn, "SELECT fullName FROM EmailFolder ORDER BY fullName"),
        )
        assertEquals(listOf("INBOX"), strings(conn, "SELECT parentFullName FROM EmailFolder WHERE parentFullName IS NOT NULL"))
        assertEquals(listOf("INBOX"), strings(conn, "SELECT folderName FROM DeletedUid"))
        conn.close()
    }
}
