package com.vayunmathur.email.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** W5: the full EmailDatabase migration chain over a v4-shaped database. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class EmailMigrationsW5Test {

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
            (4..22).map { it to it + 1 },
            EmailDatabase.migrations.map { it.startVersion to it.endVersion }.sortedBy { it.first },
        )
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
    fun v4DatabaseUpgradesToV23Shape() {
        val conn = openV4()
        migrate(conn, 4)
        assertTrue(tableExists(conn, "OutboxEntry"))
        assertTrue(tableExists(conn, "DraftEntry"))
        assertTrue(tableExists(conn, "BlockedSender"))
        assertTrue(tableExists(conn, "DeletedUid"))
        assertTrue(tableExists(conn, "EmailMessageFts"))
        assertTrue(columns(conn, "EmailMessage").containsAll(setOf("dateMillis", "snoozedUntil", "listUnsubscribe", "listUnsubscribePost", "peekContent")), columns(conn, "EmailMessage").toString())
        assertTrue(columns(conn, "EmailAccount").containsAll(setOf("provider", "imapHost", "smtpHost", "authType", "username", "signature", "allowInsecureCertificates")), columns(conn, "EmailAccount").toString())
        assertTrue(columns(conn, "OutboxEntry").containsAll(setOf("scheduledAt", "isHtml", "bcc", "inlineImageJson")), columns(conn, "OutboxEntry").toString())
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
