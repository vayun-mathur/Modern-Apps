package com.vayunmathur.sdk.games

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * An in-memory stand-in for the hub's ContentProvider, plus the record of every
 * call it received. Tests install the hub package and register this provider for
 * both hub authorities; with the package absent the client must never reach it.
 */
class FakeHubProvider : ContentProvider() {

    data class Insert(val uri: Uri, val values: Map<String, Any?>)
    data class Update(val uri: Uri, val values: Map<String, Any?>, val selection: String?)

    companion object {
        val inserts = mutableListOf<Insert>()
        val updates = mutableListOf<Update>()
        val queries = mutableListOf<Uri>()

        var insertReturnsNull = false
        var updateCount = 1
        var throwOnInsert = false
        var throwOnUpdate = false
        var throwOnQuery = false
        var existingProgress: Int? = null

        fun reset() {
            inserts.clear()
            updates.clear()
            queries.clear()
            insertReturnsNull = false
            updateCount = 1
            throwOnInsert = false
            throwOnUpdate = false
            throwOnQuery = false
            existingProgress = null
        }
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (throwOnInsert) throw IllegalStateException("provider blew up")
        inserts.add(Insert(uri, values?.valueSet()?.associate { it.key to it.value } ?: emptyMap()))
        return if (insertReturnsNull) null else Uri.withAppendedPath(uri, "row1")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (throwOnUpdate) throw IllegalStateException("provider blew up")
        updates.add(Update(uri, values?.valueSet()?.associate { it.key to it.value } ?: emptyMap(), selection))
        return updateCount
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (throwOnQuery) throw IllegalStateException("provider blew up")
        queries.add(uri)
        val progress = existingProgress
        return if (progress != null) {
            MatrixCursor(arrayOf(GameHubContract.AchievementProgressCols.PROGRESS)).apply {
                addRow(arrayOf<Any>(progress))
            }
        } else {
            MatrixCursor(arrayOf(GameHubContract.AchievementProgressCols.PROGRESS))
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}

fun installHubPackage(packageManager: android.content.pm.PackageManager, packageName: String) {
    val info = PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = android.content.pm.ApplicationInfo().apply { this.packageName = packageName }
    }
    org.robolectric.Shadows.shadowOf(packageManager).installPackage(info)
}
