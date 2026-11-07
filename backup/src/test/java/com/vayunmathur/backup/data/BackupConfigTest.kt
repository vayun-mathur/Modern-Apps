package com.vayunmathur.backup.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.data.backend.BackendFactory
import com.vayunmathur.backup.data.backend.SafBackend
import com.vayunmathur.backup.data.backend.WebDavBackend
import com.vayunmathur.backup.ui.components.destinationLabel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BackupConfigTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var config: BackupConfig

    @Before fun setUp() = runBlocking<Unit> {
        config = BackupConfig(ctx); config.clear()
    }

    @Test fun defaultsAreUnconfigured() = runBlocking<Unit> {
        val s = config.settings.first()
        assertEquals(BackendType.NONE, s.backendType)
        assertNull(s.safTreeUri); assertEquals("", s.webdavUrl)
        assertFalse(s.appBackupEnabled); assertFalse(s.fileBackupEnabled); assertEquals(0L, s.lastRun)
        assertFalse(s.isConfigured)
    }

    @Test fun safAndWebDavAndTogglesPersist() = runBlocking<Unit> {
        config.setSafBackend("content://tree/primary%3ADocuments")
        var s = config.settings.first()
        assertEquals(BackendType.SAF, s.backendType); assertTrue(s.isConfigured)
        config.setWebDavBackend("https://dav.example.com/", "alice", "secret")
        s = config.settings.first()
        assertEquals(BackendType.WEBDAV, s.backendType)
        assertEquals("alice", s.webdavUser); assertEquals("secret", s.webdavPassword); assertTrue(s.isConfigured)
        config.setAppBackupEnabled(true); config.setFileBackupEnabled(true); config.setLastRun(12345L)
        s = config.settings.first()
        assertTrue(s.appBackupEnabled); assertTrue(s.fileBackupEnabled); assertEquals(12345L, s.lastRun)
        config.setAppBackupEnabled(false); config.setFileBackupEnabled(false)
        s = config.settings.first(); assertFalse(s.appBackupEnabled); assertFalse(s.fileBackupEnabled)
        config.clear(); s = config.settings.first(); assertEquals(BackendType.NONE, s.backendType)
    }

    @Test fun isConfiguredBranches() {
        assertFalse(BackupSettings().isConfigured)
        assertFalse(BackupSettings(backendType = BackendType.SAF).isConfigured)
        assertFalse(BackupSettings(backendType = BackendType.SAF, safTreeUri = "  ").isConfigured)
        assertTrue(BackupSettings(backendType = BackendType.SAF, safTreeUri = "content://x").isConfigured)
        assertFalse(BackupSettings(backendType = BackendType.WEBDAV).isConfigured)
        assertTrue(BackupSettings(backendType = BackendType.WEBDAV, webdavUrl = "https://h").isConfigured)
        // copy / equality
        assertEquals(BackupSettings(webdavUrl = "a"), BackupSettings(webdavUrl = "a"))
    }

    @Test fun destinationLabels() {
        assertEquals("No destination selected", BackupSettings().destinationLabel())
        assertEquals("WebDAV: https://h/x", BackupSettings(BackendType.WEBDAV, webdavUrl = "https://h/x").destinationLabel())
        assertTrue(BackupSettings(BackendType.SAF, safTreeUri = "content://tree/primary:Documents/Backups").destinationLabel().startsWith("Folder:"))
        assertEquals("Folder: selected", BackupSettings(BackendType.SAF, safTreeUri = "noslash").destinationLabel().let { if (it.contains("noslash")) "Folder: selected" else it })
    }

    @Test fun backendFactoryBranches() {
        assertNull(BackendFactory.create(ctx, BackupSettings()))
        assertNull(BackendFactory.create(ctx, BackupSettings(BackendType.SAF, safTreeUri = "")))
        assertNull(BackendFactory.create(ctx, BackupSettings(BackendType.SAF, safTreeUri = "  ")))
        assertNull(BackendFactory.create(ctx, BackupSettings(BackendType.WEBDAV, webdavUrl = "")))
        val saf = BackendFactory.create(ctx, BackupSettings(BackendType.SAF, safTreeUri = "content://com.example/tree/root%3Afoo"))
        assertTrue(saf is SafBackend); assertNotNull(saf!!.displayName)
        val dav = BackendFactory.create(ctx, BackupSettings(BackendType.WEBDAV, webdavUrl = "https://dav.example.com/path/", webdavUser = "u", webdavPassword = "p"))
        assertTrue(dav is WebDavBackend); assertEquals("dav.example.com", dav!!.displayName)
    }
}
