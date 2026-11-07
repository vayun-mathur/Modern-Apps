package com.vayunmathur.securemail.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Process-wide mail database holder. Concern logic lives in the ViewModel and
 * the socket layer; this class only owns the singleton and exposes DAO access.
 */
class MailRepository private constructor(context: Context) :
    RoomRepository<MailDatabase>(context, MailDatabase::class, "securemail-db"), MailStore {

    override fun messageDao(): MessageDao = db.messageDao()
    override fun keyDao(): KeyDao = db.keyDao()
    override fun outboxDao(): OutboxDao = db.outboxDao()
    override fun identityDao(): IdentityDao = db.identityDao()

    companion object {
        @Volatile private var instance: MailRepository? = null
        fun get(context: Context): MailRepository =
            instance ?: synchronized(this) {
                instance ?: MailRepository(context).also { instance = it }
            }
    }
}
