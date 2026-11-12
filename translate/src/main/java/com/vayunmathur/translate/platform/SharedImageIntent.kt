package com.vayunmathur.translate.platform

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Pulls the image out of a Share (ACTION_SEND) or Open with (ACTION_VIEW) intent
 * aimed at the translator (#846).
 *
 * Only `content://` uris are taken: those carry the sender's read grant, while a
 * `file://` uri from another app would either be unreadable without storage
 * permission or point back into this app's own private files. A declared MIME type,
 * when present, must be an image type; the cache sniffs the bytes as well, so a lying
 * type still cannot get a non-image through.
 *
 * An intent re-delivered by a launch from Recents is ignored: its one-shot grant is
 * long gone and the image was already handled when it first arrived.
 */
object SharedImageIntent {

    /** The image uri carried by [intent], or null when it carries none we accept. */
    fun imageUri(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val type = intent.type
        if (type != null && !type.startsWith("image/")) return null
        return uri
    }
}
