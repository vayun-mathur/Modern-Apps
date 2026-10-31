package com.vayunmathur.appstore.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateEligibilityTest {

    private val self = "com.vayunmathur.appstore"

    @Test
    fun `installer of record being us counts as managed`() {
        assertTrue(UpdateEligibility.installedByStore(self, null, self))
    }

    @Test
    fun `update owner being us counts as managed`() {
        assertTrue(UpdateEligibility.installedByStore("com.android.vending", self, self))
    }

    @Test
    fun `play-installed app is not managed`() {
        assertFalse(UpdateEligibility.installedByStore("com.android.vending", null, self))
    }

    @Test
    fun `adb-sideloaded app with no installer record is not managed`() {
        assertFalse(UpdateEligibility.installedByStore(null, null, self))
    }

    @Test
    fun `another store being both installer and owner is not managed`() {
        assertFalse(
            UpdateEligibility.installedByStore(
                "org.fdroid.fdroid",
                "org.fdroid.fdroid",
                self,
            ),
        )
    }
}
