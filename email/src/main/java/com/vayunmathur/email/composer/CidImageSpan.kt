package com.vayunmathur.email.composer

import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.style.ImageSpan

/**
 * ImageSpan that holds a CID for inline email rendering.
 * [cid] is without angle brackets, e.g. "uuid@inline.local"
 * [localUri] points to the cached file used for preview and sending.
 */
class CidImageSpan(
    val cid: String,
    val localUri: Uri,
    drawable: Drawable,
    val mimeType: String,
    val fileName: String,
) : ImageSpan(drawable)
