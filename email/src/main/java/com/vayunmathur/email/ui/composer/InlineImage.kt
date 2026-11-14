package com.vayunmathur.email.ui.composer

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
/** Serializable metadata describing an inline image embedded in a draft. */
data class InlineImageMeta(
    val cid: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
)

/** An inline image in the composer with its content ID and local URI. */
data class InlineImage(
    val cid: String,
    val localUri: Uri,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long = 0L,
)

/** An inline attachment to embed in an outgoing MIME message. */
data class InlineAttachment(
    val cid: String,
    val uri: Uri,
    val mimeType: String,
    val fileName: String,
)
