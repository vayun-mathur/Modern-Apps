package com.vayunmathur.openassistant.util

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.platform.FakeAndroidKeyStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** copyUriToFile against the Robolectric content resolver, routes, backup agent config. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class CopyUriAndRouteTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun copyUriToFileCopiesContent() {
        val source = File(ctx.cacheDir, "source-image.bin")
        source.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val copied = copyUriToFile(ctx, Uri.fromFile(source))
        assertTrue(copied != null && copied.exists())
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), copied!!.readBytes().toList())
        assertTrue(copied.name.startsWith("img_") && copied.name.endsWith(".jpg"))
        copied.delete()
    }

    @Test fun copyUriToFileMissingSourceIsNull() {
        val missing = File(ctx.cacheDir, "no-such-image.bin")
        assertNull(copyUriToFile(ctx, Uri.fromFile(missing)))
        assertNull(copyUriToFile(ctx, Uri.parse("content://com.example.absent/1")))
    }

    @Test fun copyUriToFileEmptySourceIsNull() {
        val empty = File(ctx.cacheDir, "empty.bin")
        empty.writeBytes(ByteArray(0))
        assertNull(copyUriToFile(ctx, Uri.fromFile(empty)))
    }

    @Test fun routesCarryIds() {
        assertEquals(Route.ConversationPage(7L), Route.ConversationPage(7L))
        assertEquals(7L, Route.ConversationPage(7L).id)
        assertEquals(Route.SettingsPage, Route.SettingsPage)
        assertTrue(Route.ConversationPage(1L) != Route.ConversationPage(2L))
    }

    @Test fun backupAgentConfigWithoutKey() {
        FakeAndroidKeyStore.install()
        val agent = AppBackupAgent()
        assertEquals(SqlCipherDbCodec, agent.dbCodec)
        assertEquals(emptyList(), agent.extraFiles)
        // No passphrase key has been generated in this environment.
        assertEquals(emptyList(), agent.dbConfigs)
    }
}
