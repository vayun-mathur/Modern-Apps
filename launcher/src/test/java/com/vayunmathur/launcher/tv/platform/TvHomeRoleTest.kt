package com.vayunmathur.launcher.tv.platform

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [TvHomeRole] under Robolectric: without a HOME role available the
 * request falls back to the home-settings page, and default-home detection
 * resolves through the package manager.
 */
@RunWith(AndroidJUnit4::class)
class TvHomeRoleTest {

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `default home resolves to this package under Robolectric`() {
        // Robolectric resolves the HOME intent to the launcher's own
        // MainActivity (declared with the HOME category), so the check that
        // compares the resolved package with ours reads true.
        assertTrue(TvHomeRole.isDefaultHome(activity()))
    }

    @Test
    fun `request falls back to home settings when the role is unavailable`() {
        val activity = activity()
        assertTrue(TvHomeRole.requestHomeRole(activity))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_HOME_SETTINGS, started.action)
    }

    @Test
    fun `request uses the role intent when the home role is available`() {
        val activity = activity()
        val roleManager = activity.getSystemService(RoleManager::class.java)
        shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_HOME)
        assertTrue(TvHomeRole.requestHomeRole(activity))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals("android.app.role.action.REQUEST_ROLE", started.action)
        assertTrue(Intent.ACTION_MAIN != started.action)
    }
}
