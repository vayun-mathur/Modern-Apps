package com.vayunmathur.email.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts4
import androidx.room3.FtsOptions
import androidx.room3.PrimaryKey

/**
 * Full-text index over [EmailMessage.subject], sender and body.
 *
 * Uses the unicode61 tokenizer with diacritics removed so accented and
 * unaccented spellings match. Content is kept in sync with [EmailMessage];
 * see the sync triggers / content-entity wiring in [EmailDatabase].
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    contentEntity = EmailMessage::class,
)
@Entity(tableName = "EmailMessageFts")
data class EmailMessageFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    var rowid: Long = 0,
    val subject: String = "",
    val from: String = "",
    val body: String? = null,
)
