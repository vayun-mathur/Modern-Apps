package com.vayunmathur.web.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [WebDatabase]. Per-domain stores hang off here so no single
 * class exceeds the function-count budget; call sites use `repository.<domain>`.
 */
/**
 * The per-domain stores [com.vayunmathur.web.platform.WebViewModel] reads and writes.
 * Split out from [WebRepository] so view-model and page tests can supply stores backed
 * by an in-memory database instead of the SQLCipher-backed production database (whose
 * AndroidKeyStore key does not exist under Robolectric).
 */
interface WebStores {
    val history: WebHistoryStore
    val bookmarks: WebBookmarkStore
    val permissions: WebSitePermissionStore
    val storage: WebStorageStore
    val downloads: WebDownloadStore
    val installed: WebInstalledSiteStore
    val shields: WebShieldStore
}

class WebRepository private constructor(context: Context) :
    RoomRepository<WebDatabase>(context, WebDatabase::class, DB_NAME), WebStores {

    override val history = WebHistoryStore(db.historyDao())
    override val bookmarks = WebBookmarkStore(db.bookmarkDao())
    override val permissions = WebSitePermissionStore(db.sitePermissionDao())
    override val storage = WebStorageStore(db.storageInfoDao())
    override val downloads = WebDownloadStore(db.downloadDao())
    override val installed = WebInstalledSiteStore(db.installedSiteDao())
    override val shields = WebShieldStore(db.shieldSettingDao())

    companion object {
        @Volatile private var instance: WebRepository? = null
        fun get(context: Context): WebRepository =
            instance ?: synchronized(this) {
                instance ?: WebRepository(context).also { instance = it }
            }
    }
}
