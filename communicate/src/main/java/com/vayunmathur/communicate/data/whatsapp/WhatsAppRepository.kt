package com.vayunmathur.communicate.data.whatsapp

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [WhatsAppDatabase] (communicate_whatsapp.db).
 *
 * The single `buildDatabase` call site now lives here (via [RoomRepository.db]);
 * [WhatsAppDatabase.getDatabase] delegates to this repository so existing consumers
 * keep compiling unchanged. E2E `runBlocking` store bridges keep working — they
 * now obtain DAOs via this repository's database.
 */
class WhatsAppRepository private constructor(context: Context) :
    RoomRepository<WhatsAppDatabase>(context, WhatsAppDatabase::class, "communicate_whatsapp.db") {

    /** Direct database access for legacy `runBlocking` bridges; prefer suspend wrappers for new code. */
    fun database(): WhatsAppDatabase = db

    /** The singleton [WhatsAppRepository] instance holder. */
    companion object {
        @Volatile
        private var instance: WhatsAppRepository? = null

        fun get(context: Context): WhatsAppRepository =
            instance ?: synchronized(this) {
                instance ?: WhatsAppRepository(context).also { instance = it }
            }
    }
}
