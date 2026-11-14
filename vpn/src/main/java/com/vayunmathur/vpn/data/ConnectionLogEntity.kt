package com.vayunmathur.vpn.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

/**
 * Cumulative per-connection log entry.
 * Each unique flow (proto+remoteIp+remotePort+uid+localPort) gets one row that is upserted
 * with cumulative tx/rx bytes. Domain is resolved via DNS snooping + SNI.
 */
@Entity(
    indices = [
        Index(value = ["packageName", "domain", "timestampLast", "remoteIp", "uid", "protocol"]),
        Index(value = ["remoteIp", "remotePort", "protocol", "uid"]),
        Index(value = ["domain"]),
        Index(value = ["packageName"]),
    ]
)
data class ConnectionLogEntity(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val timestampStart: Long = 0,
    val timestampLast: Long = 0,
    val uid: Int = -1,
    val packageName: String? = null,
    val appLabel: String = "",
    val protocol: String = "",
    val localIp: String = "",
    val localPort: Int = 0,
    val remoteIp: String = "",
    val remotePort: Int = 0,
    val domain: String? = null,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val requestCount: Long = 1,
) : DatabaseItem

/** Immutable value describing app usage summary. */
data class AppUsageSummary(
    val packageName: String?,
    val appLabel: String,
    val totalBytes: Long,
    val totalCount: Long,
)

/** Immutable value describing domain count summary. */
data class DomainCountSummary(
    val domain: String,
    val totalCount: Long,
)

/** Immutable value describing domain bytes summary. */
data class DomainBytesSummary(
    val domain: String,
    val totalBytes: Long,
)
