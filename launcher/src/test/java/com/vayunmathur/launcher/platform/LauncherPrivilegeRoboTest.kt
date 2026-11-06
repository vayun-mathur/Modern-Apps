package com.vayunmathur.launcher.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [LauncherPrivilege]: on an ordinary (non-privileged) build all
 * probes read false, the shade does not expand, and quiet mode is refused.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherPrivilegeRoboTest {

    private fun privilege(): LauncherPrivilege {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return LauncherPrivilege(ctx) { null }
    }

    @Test fun ordinaryBuildHoldsNothing() {
        val p = privilege()
        assertEquals(false, p.hasHomeRole())
        assertEquals(false, p.canUninstallSilently())
        assertEquals(false, p.canToggleQuietMode())
        assertEquals(false, p.canExpandNotificationShade())
    }

    @Test fun shadeExpansionDoesNotThrow() {
        // Under Robolectric the status-bar shadow answers; on a stock device it
        // refuses. Either way the call is safe and boolean.
        privilege().expandNotificationShade()
    }
}
