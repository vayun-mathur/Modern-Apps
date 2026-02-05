package com.vayunmathur.maps

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.NameSearchEntity
import com.vayunmathur.maps.data.TagDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object OSM2 {
    private lateinit var db: TagDatabase
    private const val TAG = "OSM_Search"

    fun init(context: Context, ds: DataStoreUtils, database: TagDatabase) {
        this.db = database

        CoroutineScope(Dispatchers.IO).launch {
            if(ds.getLong("searchIndex") != 1L) {
                Log.d(TAG, "Search index empty. Building from binary...")
                buildIndex(context)
                ds.setLong("searchIndex", 1L)
            }
            Log.d(TAG, "Search index ready.")
        }
    }

    suspend fun search(query: String): List<Long> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        db.searchDao().search(query)
    }

    private suspend fun buildIndex(context: Context) {
        val file = context.getExternalFilesDir(null)?.resolve("names_only.bin") ?: return
        if (!file.exists()) return

        withContext(Dispatchers.IO) {
            // 1. Configure the connection OUTSIDE the transaction
            val sdb = db.openHelper.writableDatabase
            sdb.query("PRAGMA journal_mode = WAL").close()
            sdb.query("PRAGMA synchronous = NORMAL").close()

            // 2. Now start the heavy lifting
            db.withTransaction {
                RandomAccessFile(file, "r").use { raf ->
                    val channel = raf.channel
                    channel.position(8) // Skip count header

                    val buffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                    val batch = mutableListOf<NameSearchEntity>()

                    while (channel.read(buffer) != -1) {
                        buffer.flip()
                        while (buffer.remaining() >= 9) {
                            buffer.mark()
                            val nodeID = buffer.long
                            val nameLen = buffer.get().toInt() and 0xFF

                            if (buffer.remaining() >= nameLen) {
                                val nameBytes = ByteArray(nameLen)
                                buffer.get(nameBytes)
                                batch.add(NameSearchEntity(String(nameBytes), nodeID))

                                if (batch.size >= 10000) {
                                    db.searchDao().insertAll(batch)
                                    batch.clear()
                                }
                            } else {
                                buffer.reset()
                                break
                            }
                        }
                        buffer.compact()
                    }
                    if (batch.isNotEmpty()) db.searchDao().insertAll(batch)

                    // 3. Optimize FTS4 (Still inside transaction is fine for this)
                    sdb.execSQL("INSERT INTO name_search(name_search) VALUES('optimize')")
                }
            }
        }
    }
}