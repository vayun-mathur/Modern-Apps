package com.vayunmathur.web.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

/** Immutable value describing site permission. */
@Entity
data class SitePermission(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val origin: String,
    val cameraAllowed: Boolean? = null,
    val microphoneAllowed: Boolean? = null,
    val locationAllowed: Boolean? = null,
    val notificationsAllowed: Boolean? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) : DatabaseItem

/** Immutable value describing storage info. */
@Entity
data class StorageInfo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val origin: String,
    val host: String,
    val cookieCount: Int = 0,
    val hasLocalStorage: Boolean = false,
    val hasIndexedDb: Boolean = false,
    val hasServiceWorker: Boolean = false,
    val estimatedBytes: Long = 0L,
    val lastSeen: Long = System.currentTimeMillis(),
) : DatabaseItem

/** Immutable value describing download entry. */
@Entity
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    val contentLength: Long = 0L,
    val startedAt: Long = System.currentTimeMillis(),
) : DatabaseItem
