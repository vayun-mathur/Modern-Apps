package com.vayunmathur.library.log

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri

/**
 * Wires the repo logging facade's dev gate without a per-app `Application`
 * subclass.
 *
 * Every app used to declare an identical `XApplication : Application` whose
 * only job was `Log.init(BuildConfig.DEV_BUILD)` in `onCreate`. A
 * `ContentProvider` runs before `Application.onCreate`, so this single
 * provider — merged into every app from `:library:log`'s manifest — replaces
 * all of them. The dev flag is derived from the `debuggable` manifest flag,
 * which matches the old `BuildConfig.DEV_BUILD` semantics (`dev` variants
 * are debuggable, `release` variants are not).
 *
 * This provider is also where every app gets [CrashCapture]: [onCreate]
 * installs its default uncaught-exception handler (persist-then-chain, see
 * [CrashCapture]) alongside the dev gate. Both wirings are guarded so a
 * failure here can never break app startup.
 *
 * Carries no data: every method besides [onCreate] is a stub.
 */
class MaInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext
        if (appContext != null) {
            runCatching {
                val isDev =
                    appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                Log.init(isDev)
            }
            runCatching { CrashCapture.install(appContext) }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0
}
