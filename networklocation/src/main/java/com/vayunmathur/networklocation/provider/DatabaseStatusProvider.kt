package com.vayunmathur.networklocation.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.vayunmathur.networklocation.platform.OfflineDatabases

/**
 * Reports whether the offline databases have been downloaded, so Settings can label its
 * single "Network location & geocoding" row without duplicating the presence check.
 *
 * Settings runs as `android.uid.system`, which is the only caller allowed through. The
 * manifest's read permission already restricts this to the platform, and the UID check makes
 * the intended caller explicit rather than admitting anything else that happens to hold it.
 */
class DatabaseStatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceSystemCaller()
        if (uri != DatabaseStatusContract.DATABASES) return null
        val ctx = context ?: return null
        return MatrixCursor(arrayOf(DatabaseStatusContract.COLUMN_ALL_PRESENT)).apply {
            addRow(arrayOf(if (OfflineDatabases.allPresent(ctx)) 1 else 0))
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun enforceSystemCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return
        throw SecurityException("DatabaseStatusProvider: caller uid $uid is not the system")
    }
}
