package com.vayunmathur.communicate.data.whatsapp.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessageDao
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData

/**
 * Imports a decrypted `msgstore.db` (see [Crypt15Decryptor]) into the WhatsApp Room cache. Opens the
 * plain SQLite file read-only and maps the modern msgstore schema (`message` ⋈ `chat` ⋈ `jid`) into
 * [WhatsAppCachedMessage]/[WhatsAppConversation]. Media is metadata-only (WhatsApp media is
 * cloud-referenced). Dedupe is inherent: cached rows are keyed by the message `key_id`.
 *
 * ⚠️ msgstore schema drifts across WhatsApp versions; column reads are defensive (`getColumnIndex`
 * null-checks) and failures are collected into [ImportResult.errors] rather than aborting.
 */
object BackupImporter {

    private const val TAG = "WABackupImporter"
    private const val BATCH = 500

    /** The outcome of a WhatsApp backup import: conversations and messages imported. */
    data class ImportResult(
        val conversationCount: Int,
        val messageCount: Int,
        val errors: List<String>,
    )

    suspend fun import(context: Context, crypt15: ByteArray, backupKey: ByteArray): ImportResult {
        val errors = mutableListOf<String>()
        val dbFile = try {
            Crypt15Decryptor.decryptToFile(crypt15, backupKey, context.cacheDir)
        } catch (expected: Throwable) {
            return ImportResult(0, 0, listOf("Decrypt failed: ${expected.message}"))
        }

        val room = WhatsAppDatabase.getDatabase(context)
        val counts = try {
            importMessages(dbFile, room, errors)
        } catch (expected: Throwable) {
            Log.error(TAG, "import failed", expected)
            errors.add("Import failed: ${expected.message}")
            0 to 0
        } finally {
            runCatching { dbFile.delete() }
        }
        return ImportResult(counts.first, counts.second, errors)
    }

    /** Copy messages + conversations; returns (conversationCount, messageCount). */
    private suspend fun importMessages(
        dbFile: java.io.File,
        room: WhatsAppDatabase,
        errors: MutableList<String>,
    ): Pair<Int, Int> {
        var messageCount = 0
        val conversationJids = HashSet<String>()
        val msgDao = room.cachedMessageDao()
        val convDao = room.conversationDao()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { src ->
            val sql = """
                SELECT m.key_id AS key_id, m.from_me AS from_me, m.timestamp AS ts,
                       m.text_data AS text_data, j.raw_string AS chat_jid
                FROM message m
                JOIN chat c ON m.chat_row_id = c._id
                JOIN jid j ON c.jid_row_id = j._id
                ORDER BY m.timestamp ASC
            """.trimIndent()
            src.rawQuery(sql, null).use { cur ->
                val cols = MessageColumns(cur)
                if (!cols.valid) {
                    errors.add("Unexpected msgstore schema (missing key_id/jid)")
                } else {
                    messageCount = importCursor(cur, cols, msgDao, conversationJids)
                }
            }
        }
        for (jid in conversationJids) {
            convDao.upsert(WhatsAppConversation(chatJid = jid))
        }
        return conversationJids.size to messageCount
    }

    private data class MessageColumns(
        val key: Int,
        val from: Int,
        val ts: Int,
        val text: Int,
        val jid: Int,
    ) {
        constructor(cur: android.database.Cursor) : this(
            cur.getColumnIndex("key_id"),
            cur.getColumnIndex("from_me"),
            cur.getColumnIndex("ts"),
            cur.getColumnIndex("text_data"),
            cur.getColumnIndex("chat_jid"),
        )

        val valid: Boolean get() = key >= 0 && jid >= 0
    }

    /** Copy one cursor's rows in batches; returns the message count. */
    private suspend fun importCursor(
        cur: android.database.Cursor,
        cols: MessageColumns,
        msgDao: WhatsAppCachedMessageDao,
        conversationJids: HashSet<String>,
    ): Int {
        var messageCount = 0
        val batch = ArrayList<WhatsAppCachedMessage>(BATCH)
        while (cur.moveToNext()) {
            val jid = cur.getString(cols.jid)
            val keyId = cur.getString(cols.key)
            if (jid != null && keyId != null) {
                val body = if (cols.text >= 0) cur.getString(cols.text) ?: "" else ""
                val ts = if (cols.ts >= 0) cur.getLong(cols.ts) else 0L
                val outgoing = cols.from >= 0 && cur.getInt(cols.from) == 1
                conversationJids.add(jid)
                val sd = WhatsAppServiceData(isGroup = jid.endsWith("@g.us"))
                batch.add(
                    WhatsAppCachedMessage(
                        messageId = keyId,
                        conversationJid = jid,
                        body = body,
                        timestamp = ts,
                        outgoing = outgoing,
                        serviceData = sd.serialize(),
                    ),
                )
                if (batch.size >= BATCH) {
                    msgDao.upsertAll(batch)
                    messageCount += batch.size
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) {
            msgDao.upsertAll(batch)
            messageCount += batch.size
        }
        return messageCount
    }
}
