package com.vayunmathur.communicate.data.signal

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [SignalDatabase] (communicate_signal.db).
 *
 * The single `buildDatabase` call site now lives here (via [RoomRepository.db]);
 * [SignalDatabase.getDatabase] delegates to this repository so existing consumers
 * keep compiling unchanged. E2E `runBlocking` store bridges keep working — they
 * now obtain DAOs via this repository's database.
 */
class SignalRepository private constructor(context: Context) :
    RoomRepository<SignalDatabase>(context, SignalDatabase::class, "communicate_signal.db") {

    /** Direct database access for legacy `runBlocking` bridges; prefer suspend wrappers for new code. */
    fun database(): SignalDatabase = db

    /** The singleton [SignalRepository] instance holder. */
    companion object {
        @Volatile
        private var instance: SignalRepository? = null

        fun get(context: Context): SignalRepository =
            instance ?: synchronized(this) {
                instance ?: SignalRepository(context).also { instance = it }
            }
    }
}
