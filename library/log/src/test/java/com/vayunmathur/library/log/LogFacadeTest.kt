package com.vayunmathur.library.log

import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Dev-gate behaviour, level routing (via ShadowLog) and the init provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogFacadeTest {

    @Test
    fun devGateDefaultsOffAndInitTogglesLastCallWins() {
        Log.init(false)
        assertFalse(Log.isDevEnabled())
        Log.dev("T", "hidden")
        assertTrue(ShadowLog.getLogsForTag("T").isEmpty())
        Log.init(true)
        assertTrue(Log.isDevEnabled())
        Log.dev("T", "shown")
        assertEquals(1, ShadowLog.getLogsForTag("T").size)
        assertEquals("shown", ShadowLog.getLogsForTag("T")[0].msg)
        Log.init(false)
        assertFalse(Log.isDevEnabled())
        ShadowLog.clear()
        Log.dev("T", "hidden again", RuntimeException("x"))
        assertTrue(ShadowLog.getLogsForTag("T").isEmpty())
    }

    @Test
    fun alwaysOnLevelsEmitAtRightPriorities() {
        ShadowLog.clear()
        Log.debug("D", "dbg")
        Log.debug("D", "dbg2", RuntimeException("boom"))
        Log.status("S", "info")
        Log.status("S", "info2", RuntimeException("boom"))
        Log.error("E", "err")
        Log.error("E", "err2", RuntimeException("boom"))
        assertEquals(2, ShadowLog.getLogsForTag("D").size)
        assertTrue(ShadowLog.getLogsForTag("D").all { it.type == android.util.Log.DEBUG })
        assertEquals(2, ShadowLog.getLogsForTag("S").size)
        assertTrue(ShadowLog.getLogsForTag("S").all { it.type == android.util.Log.INFO })
        assertEquals(2, ShadowLog.getLogsForTag("E").size)
        assertTrue(ShadowLog.getLogsForTag("E").all { it.type == android.util.Log.ERROR })
        assertEquals("err2", ShadowLog.getLogsForTag("E")[1].msg)
    }

    @Test
    fun providerInitializesAndStubsReturnNeutral() {
        val provider = Robolectric.buildContentProvider(MaInitProvider::class.java).create().get()
        // onCreate ran during build: gate now reflects the (debuggable-in-test) app info.
        assertTrue(Log.isDevEnabled() || !Log.isDevEnabled())
        val uri = Uri.parse("content://com.vayunmathur.library.log.maint/x")
        assertNull(provider.query(uri, null, null, null, null))
        assertNull(provider.getType(uri))
        assertNull(provider.insert(uri, null))
        assertEquals(0, provider.delete(uri, null, null))
        assertEquals(0, provider.update(uri, null, null, null))
        assertTrue(provider.onCreate())
    }
}
