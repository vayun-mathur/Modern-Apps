package com.vayunmathur.communicate.whatsapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.backup.BackupImporter
import com.vayunmathur.library.util.databases
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp backup): BackupImporter end-to-end offline - build a
 * real msgstore SQLite file, wrap it in a crypt15 blob (same HKDF/GCM recipe as
 * the decryptor tests), import it into an in-memory Room cache, and assert the
 * mapped rows. Plus the decrypt/schema failure results.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2BackupImporterTest {

    private lateinit var context: Context
    private lateinit var room: WhatsAppDatabase
    private val backupKey = ByteArray(32) { (it * 7 + 1).toByte() }

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        room = Room.inMemoryDatabaseBuilder(context, WhatsAppDatabase::class.java)
            .setSingleConnectionPool()
            .allowMainThreadQueries().build()
        // BackupImporter reaches its Room cache through WhatsAppRepository, whose
        // builder consults this shared cache first.
        synchronized(databases) { databases[WhatsAppDatabase::class] = room }
    }

    private fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var c = 1
        while (pos < len) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t); mac.update(info); mac.update(c.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; c++
        }
        return out
    }

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            var b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) b = b or 0x80
            out.write(b)
            if (x == 0L) break
        }
        return out.toByteArray()
    }

    private fun encryptBlob(plainDb: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(16) { (it + 3).toByte() }
        val inner = ByteArrayOutputStream()
        inner.write(0x0A); inner.write(varint(16)); inner.write(iv)
        val innerBytes = inner.toByteArray()
        val header = ByteArrayOutputStream()
        header.write(varint(innerBytes.size.toLong())); header.write(innerBytes)
        val aesKey = hkdf(key, ByteArray(32), "backup encryption".toByteArray(Charsets.UTF_8), 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val gzipped = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(plainDb) } }.toByteArray()
        return header.toByteArray() + cipher.doFinal(gzipped)
    }

    /** A minimal modern msgstore: jid/chat/message with three importable rows. */
    private fun msgstoreBytes(): ByteArray {
        val file = File(context.cacheDir, "msgstore_fixture.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE jid (_id INTEGER PRIMARY KEY, raw_string TEXT)")
        db.execSQL("CREATE TABLE chat (_id INTEGER PRIMARY KEY, jid_row_id INTEGER)")
        db.execSQL(
            "CREATE TABLE message (key_id TEXT, from_me INTEGER, timestamp INTEGER, text_data TEXT, chat_row_id INTEGER)",
        )
        db.execSQL("INSERT INTO jid VALUES (1, '777@s.whatsapp.net'), (2, '12345@g.us')")
        db.execSQL("INSERT INTO chat VALUES (1, 1), (2, 2)")
        db.execSQL(
            "INSERT INTO message VALUES " +
                "('K1', 0, 1700000000000, 'hello from them', 1), " +
                "('K2', 1, 1700000060000, 'reply from me', 1), " +
                "('K3', 0, 1700000120000, 'group hello', 2), " +
                "(NULL, 0, 1700000180000, 'keyless row is skipped', 1)",
        )
        db.close()
        return file.readBytes().also { file.delete() }
    }

    @Test
    fun `import maps msgstore rows into the room cache`() = runTest {
        val result = BackupImporter.import(context, encryptBlob(msgstoreBytes(), backupKey), backupKey)
        assertEquals(emptyList(), result.errors)
        assertEquals(2, result.conversationCount)
        assertEquals(3, result.messageCount)
        val dm = room.cachedMessageDao().getForConversation("777@s.whatsapp.net")
        assertEquals(listOf("hello from them", "reply from me"), dm.map { it.body })
        assertEquals(listOf(false, true), dm.map { it.outgoing })
        assertEquals(1_700_000_000_000L, dm.first().timestamp)
        assertNotNull(room.cachedMessageDao().get("K3"))
        assertNotNull(room.conversationDao().getConversation("12345@g.us"))
        assertNotNull(room.conversationDao().getConversation("777@s.whatsapp.net"))
        // Re-importing the same backup is deduped by message key.
        val again = BackupImporter.import(context, encryptBlob(msgstoreBytes(), backupKey), backupKey)
        assertEquals(3, again.messageCount)
        assertEquals(2, room.cachedMessageDao().getForConversation("777@s.whatsapp.net").size)
    }

    @Test
    fun `decrypt failures report instead of throwing`() = runTest {
        val garbage = BackupImporter.import(context, byteArrayOf(1, 2, 3), backupKey)
        assertEquals(0, garbage.messageCount)
        assertTrue(garbage.errors.single().startsWith("Decrypt failed"), garbage.errors.toString())
        val wrongKey = BackupImporter.import(
            context, encryptBlob(msgstoreBytes(), backupKey), ByteArray(32) { 99 },
        )
        assertEquals(0, wrongKey.messageCount)
        assertTrue(wrongKey.errors.single().startsWith("Decrypt failed"), wrongKey.errors.toString())
    }

    @Test
    fun `a decryptable non-msgstore payload reports a schema failure`() = runTest {
        val result = BackupImporter.import(
            context, encryptBlob("SQLite format 3\u0000not-a-real-msgstore".toByteArray(Charsets.ISO_8859_1), backupKey), backupKey,
        )
        assertEquals(0, result.messageCount)
        assertEquals(0, result.conversationCount)
        assertTrue(result.errors.isNotEmpty(), "expected a reported failure, got success")
    }
}
