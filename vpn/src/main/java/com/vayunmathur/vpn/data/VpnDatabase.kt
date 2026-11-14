package com.vayunmathur.vpn.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "vpn-db"

/** Immutable value describing vpn config entity. */
@Entity
data class VpnConfigEntity(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String = "",
    val privateKey: String = "",
    val publicKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: Int = 1280,
    val peerPublicKey: String = "",
    val peerPresharedKey: String = "",
    val peerAllowedIPs: String = "0.0.0.0/0, ::/0",
    val peerEndpoint: String = "",
    val peerKeepalive: Int = 25,
    val lastUsed: Long = 0,
) : DatabaseItem

fun VpnConfigEntity.toModel() = VpnConfig(
    id = id, name = name, privateKey = privateKey, publicKey = publicKey,
    address = address, dns = dns, mtu = mtu,
    peerPublicKey = peerPublicKey, peerPresharedKey = peerPresharedKey,
    peerAllowedIPs = peerAllowedIPs, peerEndpoint = peerEndpoint, peerKeepalive = peerKeepalive,
    lastUsed = lastUsed,
)

fun VpnConfig.toEntity() = VpnConfigEntity(
    id = id, name = name, privateKey = privateKey, publicKey = publicKey,
    address = address, dns = dns, mtu = mtu,
    peerPublicKey = peerPublicKey, peerPresharedKey = peerPresharedKey,
    peerAllowedIPs = peerAllowedIPs, peerEndpoint = peerEndpoint, peerKeepalive = peerKeepalive,
    lastUsed = lastUsed,
)

/** Contract for vpn config dao. */
@Dao
interface VpnConfigDao {
    @Query("SELECT * FROM VpnConfigEntity ORDER BY lastUsed DESC, id DESC")
    fun flowAll(): Flow<List<VpnConfigEntity>>

    @Query("SELECT * FROM VpnConfigEntity WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VpnConfigEntity?

    @Query("SELECT * FROM VpnConfigEntity")
    suspend fun getAll(): List<VpnConfigEntity>

    @Upsert
    suspend fun upsert(entity: VpnConfigEntity): Long

    @Delete
    suspend fun delete(entity: VpnConfigEntity): Int

    @Query("UPDATE VpnConfigEntity SET lastUsed = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `ConnectionLogEntity` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `timestampStart` INTEGER NOT NULL,
                `timestampLast` INTEGER NOT NULL,
                `uid` INTEGER NOT NULL,
                `packageName` TEXT,
                `appLabel` TEXT NOT NULL,
                `protocol` TEXT NOT NULL,
                `localIp` TEXT NOT NULL,
                `localPort` INTEGER NOT NULL,
                `remoteIp` TEXT NOT NULL,
                `remotePort` INTEGER NOT NULL,
                `domain` TEXT,
                `txBytes` INTEGER NOT NULL,
                `rxBytes` INTEGER NOT NULL,
                `requestCount` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS" +
                " `index_ConnectionLogEntity_packageName_domain_timestampLast_remoteIp_uid_protocol`" +
                " ON `ConnectionLogEntity`" +
                " (`packageName`, `domain`, `timestampLast`, `remoteIp`, `uid`, `protocol`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ConnectionLogEntity_remoteIp_remotePort_protocol_uid`" +
                " ON `ConnectionLogEntity` (`remoteIp`, `remotePort`, `protocol`, `uid`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ConnectionLogEntity_domain`" +
                " ON `ConnectionLogEntity` (`domain`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ConnectionLogEntity_packageName`" +
                " ON `ConnectionLogEntity` (`packageName`)",
        )
    }
}

/** Vpn Database: a room database implementation for vpn database. */
@Database(entities = [VpnConfigEntity::class, ConnectionLogEntity::class], version = 2, exportSchema = false)
abstract class VpnDatabase : RoomDatabase() {
    abstract fun vpnConfigDao(): VpnConfigDao
    abstract fun connectionLogDao(): ConnectionLogDao

    /** Shared constants and helpers for VpnDatabase. */
    companion object : DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2)

        /**
         * The process-wide database. Delegates to [VpnRepository] so the single
         * [RoomRepository]-owned instance is reused; every caller of
         * `VpnDatabase.get(ctx)` keeps working unchanged.
         */
        fun get(context: Context): VpnDatabase = VpnRepository.get(context).database()
    }
}
