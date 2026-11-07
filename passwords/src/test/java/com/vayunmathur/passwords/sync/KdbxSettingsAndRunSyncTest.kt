package com.vayunmathur.passwords.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxSettingsAndRunSyncTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun settingsRoundTrip() = runBlocking {
        KdbxSyncSettings.setEnabled(ctx, true)
        assertTrue(KdbxSyncSettings.enabled(ctx))
        assertTrue(KdbxSyncSettings.enabledFlow(ctx).first())
        KdbxSyncSettings.setEnabled(ctx, false)
        assertFalse(KdbxSyncSettings.enabled(ctx))

        KdbxSyncSettings.setDocumentUri(ctx, "content://vault.kdbx")
        assertEquals("content://vault.kdbx", KdbxSyncSettings.documentUri(ctx))
        KdbxSyncSettings.setDocumentUri(ctx, "")
        assertNull(KdbxSyncSettings.documentUri(ctx))
    }

    @Test fun recordSuccessFailureAndQuarantine() = runBlocking {
        KdbxSyncSettings.recordSuccess(ctx)
        assertEquals(KdbxSyncSettings.STATUS_OK, KdbxSyncSettings.statusFlow(ctx).first())
        assertTrue(KdbxSyncSettings.lastSyncFlow(ctx).first() > 0)

        KdbxSyncSettings.recordFailure(ctx, "file_missing")
        assertEquals(KdbxSyncSettings.STATUS_ERROR, KdbxSyncSettings.statusFlow(ctx).first())
        assertEquals("file_missing", KdbxSyncSettings.errorFlow(ctx).first())

        KdbxSyncSettings.recordQuarantine(ctx, KdbxSyncSettings.QUARANTINE_FOREIGN_DELETE)
        assertEquals(KdbxSyncSettings.STATUS_QUARANTINED, KdbxSyncSettings.statusFlow(ctx).first())
        assertEquals("foreign_delete", KdbxSyncSettings.quarantineFlow(ctx).first())
        assertTrue(KdbxSyncSettings.quarantineAtFlow(ctx).first() > 0)

        KdbxSyncSettings.clearQuarantine(ctx)
        assertEquals("", KdbxSyncSettings.quarantineFlow(ctx).first())
        assertEquals("", KdbxSyncSettings.statusFlow(ctx).first())
    }

    @Test fun runSyncNotConfiguredWhenDisabled() = runBlocking {
        KdbxSyncSettings.setEnabled(ApplicationProvider.getApplicationContext(), false)
        val db = androidx.room3.Room.inMemoryDatabaseBuilder(
            ctx, com.vayunmathur.passwords.data.PasswordDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            assertEquals(KdbxSyncResult.NotConfigured, runKdbxSync(ctx, db))
        } finally {
            db.close()
        }
    }

    @Test fun errorCodes() {
        assertEquals("file_missing", KdbxSyncResult.FileMissing.errorCode())
        assertEquals("wrong_password", KdbxSyncResult.WrongPassword.errorCode())
        assertEquals("verify_failed", KdbxSyncResult.VerifyFailed.errorCode())
        assertEquals("quarantined", KdbxSyncResult.Quarantined(2).errorCode())
        assertEquals("boom", KdbxSyncResult.Error("boom").errorCode())
        assertEquals("", KdbxSyncResult.NotConfigured.errorCode())
        assertEquals("", KdbxSyncResult.Success(0, 0, 0, 0).errorCode())
    }

    // KdbxPasswordHelper.isKeyGenerated needs the AndroidKeyStore provider,
    // which Robolectric 4.17 does not shadow, so it is covered on-device only.
}
