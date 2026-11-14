package com.vayunmathur.vpn.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Contract for connection log dao. */
@Dao
interface ConnectionLogDao {

    @Query("""
        SELECT packageName as packageName, MAX(appLabel) as appLabel,
               SUM(txBytes + rxBytes) as totalBytes, SUM(requestCount) as totalCount
        FROM ConnectionLogEntity
        GROUP BY COALESCE(packageName, appLabel)
        ORDER BY totalBytes DESC LIMIT 200
    """)
    fun flowTopApps(): Flow<List<AppUsageSummary>>

    @Query("""
        SELECT domain as domain, SUM(requestCount) as totalCount
        FROM ConnectionLogEntity
        WHERE domain IS NOT NULL AND domain != ''
        GROUP BY domain ORDER BY totalCount DESC LIMIT 200
    """)
    fun flowDomainsByCount(): Flow<List<DomainCountSummary>>

    @Query("""
        SELECT domain as domain, SUM(txBytes + rxBytes) as totalBytes
        FROM ConnectionLogEntity
        WHERE domain IS NOT NULL AND domain != ''
        GROUP BY domain ORDER BY totalBytes DESC LIMIT 200
    """)
    fun flowDomainsByBytes(): Flow<List<DomainBytesSummary>>

    /** UIDs that were logged before their package could be named (e.g. package-visibility filtering). */
    @Query("SELECT DISTINCT uid FROM ConnectionLogEntity WHERE packageName IS NULL AND uid > 0")
    suspend fun unnamedUids(): List<Int>

    @Query("UPDATE ConnectionLogEntity SET packageName = :packageName, appLabel = :appLabel WHERE uid = :uid")
    suspend fun nameUid(uid: Int, packageName: String, appLabel: String)

    @Upsert
    suspend fun upsertAll(entities: List<ConnectionLogEntity>): List<Long>

    @Query("DELETE FROM ConnectionLogEntity")
    suspend fun deleteAll()

    /**
     * A flow is identified by its 4-tuple only — deliberately *not* by uid, since attribution can
     * arrive several packets late and must be allowed to upgrade an already-written row in place.
     */
    @Query("""
        SELECT * FROM ConnectionLogEntity
        WHERE remoteIp = :remoteIp AND remotePort = :remotePort AND protocol = :protocol AND localPort = :localPort
        LIMIT 1
    """)
    suspend fun findIdentical(remoteIp: String, remotePort: Int, protocol: String, localPort: Int): ConnectionLogEntity?
}
