package com.vayunmathur.web.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebDatabaseTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: WebDatabase = Room.inMemoryDatabaseBuilder(context, WebDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @AfterTest fun closeDb() = db.close()

    @Test fun historyOrdersNewestFirstAndSearches() = runTest {
        val dao = db.historyDao()
        dao.upsert(HistoryEntry(url = "https://old.example", title = "Old", visitedAt = 100))
        dao.upsert(HistoryEntry(url = "https://new.example", title = "Cats", visitedAt = 900))
        assertEquals(listOf("Cats", "Old"), dao.allFlow().first().map { it.title })
        assertEquals(listOf("Cats"), dao.searchFlow("cat").first().map { it.title })
        assertEquals(listOf("Cats"), dao.recent(1).map { it.title })
        dao.deleteBefore(500)
        assertEquals(listOf("Cats"), dao.allFlow().first().map { it.title })
        dao.clearAll()
        assertTrue(dao.allFlow().first().isEmpty())
    }

    @Test fun bookmarksFoldersAndByUrl() = runTest {
        val dao = db.bookmarkDao()
        val store = WebBookmarkStore(dao)
        val folderId = store.upsertFolder(BookmarkFolder(name = "Work"))
        val id = store.upsert(Bookmark(url = "https://a.example", title = "A", folderId = folderId))
        store.upsert(Bookmark(url = "https://b.example", title = "B"))
        assertEquals("A", store.byUrl("https://a.example")?.title)
        assertEquals("A", store.byUrlFlow("https://a.example").first()?.title)
        assertEquals(listOf("A"), store.byFolderFlow(folderId).first().map { it.title })
        assertEquals(listOf("Work"), store.foldersFlow().first().map { it.name })
        store.deleteByFolder(folderId)
        assertNull(store.byUrl("https://a.example"))
        val b = store.byUrl("https://b.example")
        assertNotNull(b)
        store.delete(b)
        assertNull(store.byUrl("https://b.example"))
        val folder = store.foldersFlow().first().single()
        store.deleteFolder(folder)
        assertTrue(store.foldersFlow().first().isEmpty())
        assertTrue(id > 0)
    }

    @Test fun permissionsStorageDownloadsInstalledAndShields() = runTest {
        val permStore = WebSitePermissionStore(db.sitePermissionDao())
        permStore.upsert(SitePermission(origin = "https://x.example", cameraAllowed = true))
        assertEquals(true, permStore.byOrigin("https://x.example")?.cameraAllowed)
        assertEquals(true, permStore.byOriginFlow("https://x.example").first()?.cameraAllowed)
        permStore.deleteOrigin("https://x.example")
        assertNull(permStore.byOrigin("https://x.example"))
        permStore.upsert(SitePermission(origin = "https://y.example"))
        permStore.clearAll()
        assertTrue(permStore.allFlow().first().isEmpty())

        val storage = WebStorageStore(db.storageInfoDao())
        storage.upsert(StorageInfo(origin = "https://x.example", host = "x.example", cookieCount = 3))
        assertEquals(3, storage.byOrigin("https://x.example")?.cookieCount)
        storage.deleteOrigin("https://x.example")
        assertNull(storage.byOrigin("https://x.example"))

        val downloads = WebDownloadStore(db.downloadDao())
        val dlId = downloads.upsert(DownloadEntry(url = "https://x/f", fileName = "f.txt"))
        assertEquals(listOf("f.txt"), downloads.allFlow().first().map { it.fileName })
        downloads.deleteById(dlId)
        assertTrue(downloads.allFlow().first().isEmpty())
        downloads.upsert(DownloadEntry(url = "https://x/g", fileName = "g.txt"))
        downloads.clearAll()
        assertTrue(downloads.allFlow().first().isEmpty())

        val installed = WebInstalledSiteStore(db.installedSiteDao())
        installed.upsert(InstalledSite(id = "s1", url = "https://x.example", title = "X", origin = "https://x.example"))
        assertEquals("X", installed.byId("s1")?.title)
        assertEquals("X", installed.byOrigin("https://x.example")?.title)
        installed.deleteById("s1")
        assertNull(installed.byId("s1"))

        val shields = WebShieldStore(db.shieldSettingDao())
        shields.upsert(ShieldSetting.from("x.example", ShieldsSettings(level = ShieldLevel.OFF, blockTrackers = false)))
        val loaded = shields.byHost("x.example")
        assertEquals(ShieldLevel.OFF, loaded?.toSettings()?.level)
        assertEquals(false, loaded?.toSettings()?.blockTrackers)
        assertEquals(1, shields.all().size)
        shields.deleteHost("x.example")
        assertNull(shields.byHost("x.example"))
        shields.upsert(ShieldSetting(host = "z.example"))
        shields.clearAll()
        assertTrue(shields.allFlow().first().isEmpty())
    }

    @Test fun shieldSettingRoundTripAndBadLevel() {
        val s = ShieldSetting.from("a.example", ShieldsSettings(level = ShieldLevel.STANDARD, httpsUpgrade = false))
        assertEquals("STANDARD", s.level)
        assertEquals(ShieldLevel.STANDARD, s.toSettings().level)
        assertEquals(false, s.toSettings().httpsUpgrade)
        assertNull(ShieldSetting(host = "b", level = "NOPE").toSettings().level)
    }

    @Test fun historyStoreWrappers() = runTest {
        val store = WebHistoryStore(db.historyDao())
        store.upsert(HistoryEntry(url = "https://h.example", title = "H", visitedAt = 5))
        assertEquals(listOf("H"), store.searchFlow("h.example").first().map { it.title })
        assertEquals(listOf("H"), store.recent(10).map { it.title })
        store.deleteBefore(10)
        assertTrue(store.allFlow().first().isEmpty())
    }
}
