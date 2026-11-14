package com.vayunmathur.web.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

/** Immutable value describing history entry. */
@Entity
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val url: String,
    val title: String = "",
    val visitedAt: Long = System.currentTimeMillis(),
    val faviconUrl: String? = null,
) : DatabaseItem
