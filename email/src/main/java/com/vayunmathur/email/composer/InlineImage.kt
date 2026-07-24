package com.vayunmathur.email.composer

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
data class InlineImageMeta(
    val cid: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
)

data class InlineImage(
    val cid: String,
    val localUri: Uri,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long = 0L,
)

data class InlineAttachment(
    val cid: String,
    val uri: Uri,
    val mimeType: String,
    val fileName: String,
)
