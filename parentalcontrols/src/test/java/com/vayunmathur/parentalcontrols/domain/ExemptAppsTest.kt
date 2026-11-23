package com.vayunmathur.parentalcontrols.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExemptAppsTest {

    private val self = "com.vayunmathur.parentalcontrols"
    private val home = "com.example.launcher"
    private val dialer = "com.example.dialer"

    @Test
    fun settingsIsAlwaysExempt() {
        assertEquals(
            ExemptReason.Settings,
            exemptionFor("com.android.settings", self, home, dialer),
        )
        assertTrue("com.android.settings" in SETTINGS_PACKAGES)
    }

    @Test
    fun selfHomeAndDialerRolesAreExempt() {
        assertEquals(ExemptReason.Self, exemptionFor(self, self, home, dialer))
        assertEquals(ExemptReason.Home, exemptionFor(home, self, home, dialer))
        assertEquals(ExemptReason.Dialer, exemptionFor(dialer, self, home, dialer))
    }

    @Test
    fun ordinaryAppsAreNotExempt() {
        assertNull(exemptionFor("com.example.game", self, home, dialer))
        assertFalse(isExempt("com.example.game", self, home, dialer))
    }

    @Test
    fun unresolvedRolesLeaveOnlySelfAndSettings() {
        assertEquals(ExemptReason.Self, exemptionFor(self, self, null, null))
        assertEquals(ExemptReason.Settings, exemptionFor("com.android.settings", self, null, null))
        // A package that would be the launcher on another device is governed here,
        // because exemption follows the resolved role, not the package name.
        assertNull(exemptionFor(home, self, null, null))
        assertNull(exemptionFor(dialer, self, null, null))
    }
}
