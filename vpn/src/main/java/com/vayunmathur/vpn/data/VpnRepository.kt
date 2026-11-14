package com.vayunmathur.vpn.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [VpnDatabase]. All access goes through here (or the thin
 * [VpnDatabase.get] delegate kept for existing callers) so the database is built
 * exactly once via the shared [RoomRepository] machinery.
 */
class VpnRepository private constructor(context: Context) :
    RoomRepository<VpnDatabase>(context, VpnDatabase::class, DB_NAME) {

    /** The owned database instance (for callers that still use the DAOs directly). */
    fun database(): VpnDatabase = db

    val vpnConfigDao: VpnConfigDao get() = db.vpnConfigDao()
    val connectionLogDao: ConnectionLogDao get() = db.connectionLogDao()

    /** Shared constants and helpers for VpnRepository. */
    companion object {
        @Volatile
        private var instance: VpnRepository? = null

        fun get(context: Context): VpnRepository =
            instance ?: synchronized(this) {
                instance ?: VpnRepository(context).also { instance = it }
            }
    }
}
