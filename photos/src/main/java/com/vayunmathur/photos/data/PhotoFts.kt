package com.vayunmathur.photos.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions
import androidx.room3.PrimaryKey

/**
 * Full-text index over [Photo.ocrText] and [Photo.name].
 *
 * Restores the FTS search dropped in migration 8->9. Uses the unicode61
 * tokenizer with diacritics removed; [Photo] is the content table, and
 * Room generates the `room_fts_content_sync_*` triggers that keep this
 * index in step with inserts, updates and deletes on `Photo` (including
 * the column-targeted OCR writes in [PhotoScanDao]).
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    contentEntity = Photo::class,
)
@Entity(tableName = "PhotoFts")
data class PhotoFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    var rowid: Long = 0,
    val ocrText: String? = null,
    val name: String = "",
)
