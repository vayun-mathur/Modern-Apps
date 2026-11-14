package com.vayunmathur.web.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

/** Immutable value describing bookmark. */
@Entity
data class Bookmark(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val url: String,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val faviconUrl: String? = null,
    val folderId: Long? = null,
) : DatabaseItem

/** Immutable value describing bookmark folder. */
@Entity
data class BookmarkFolder(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
) : DatabaseItem
