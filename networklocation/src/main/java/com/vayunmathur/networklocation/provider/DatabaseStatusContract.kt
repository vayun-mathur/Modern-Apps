package com.vayunmathur.networklocation.provider

import android.net.Uri
import androidx.core.net.toUri

/**
 * Wire format of [DatabaseStatusProvider], so the provider and its callers agree on one
 * definition rather than two copies of the same strings.
 *
 * The only caller today is the platform Settings app, which lives in the OS tree and cannot
 * compile against this module — it repeats these literals. Changing anything here means
 * changing `patches/settings.patch` in the MAOS tree as well.
 */
object DatabaseStatusContract {

    const val AUTHORITY = "com.vayunmathur.networklocation.status"

    /** Single-row table describing whether the offline databases are on disk. */
    val DATABASES: Uri = "content://$AUTHORITY/databases".toUri()

    /** 1 when every offline database is present, 0 otherwise. */
    const val COLUMN_ALL_PRESENT = "all_present"
}
