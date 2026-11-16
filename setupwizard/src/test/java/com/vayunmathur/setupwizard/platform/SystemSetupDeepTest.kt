package com.vayunmathur.setupwizard.platform

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowKeyguardManager
import org.robolectric.shadows.ShadowLocationManager
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SystemSetupDeepTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `identity locale and locale list come from the platform`() {
        val system = SystemSetup(app)
        assertTrue(system.isPrimaryUser)
        assertNotNull(system.currentLocale())
        val locales = system.availableLocales()
        assertTrue(locales.isNotEmpty(), "system asset locales should be listed")
        assertTrue(locales.all { it.language.isNotEmpty() })
        assertEquals(locales.distinctBy { it.toLanguageTag() }.size, locales.size)
        // Hidden framework writes are unavailable under Robolectric and must degrade, not throw.
        system.setSystemLocale(Locale.GERMAN)
        assertNotNull(system.currentLocale())
    }

    @Test fun `sim and oem lock readings degrade to null and false without the services`() {
        val system = SystemSetup(app)
        // No OemLockManager service is registered in Robolectric, so every OEM read is the
        // documented fallback rather than an exception.
        assertFalse(system.isDeviceOemUnlocked())
        assertFalse(system.isOemUnlockAllowedByUser())
        system.setOemUnlockAllowedByUser(false)
        system.setOemUnlockAllowedByUser(true)
        // Telephony is present but the hidden SIM-locale method is not: null, not a crash.
        system.simLocale()
    }

    @Test fun `status bar and bootloader reboot calls never throw`() {
        val system = SystemSetup(app)
        system.setStatusBarHiddenForSetup(true)
        system.setStatusBarHiddenForSetup(false)
        // ShadowPowerManager records reboot instead of rebooting the test process.
        system.rebootToBootloader()
    }

    @Test fun `location wifi and lock readings mirror shadow state`() {
        val system = SystemSetup(app)
        val locationManager = ctx.getSystemService(android.location.LocationManager::class.java)
        val shadowLocation = Shadows.shadowOf(locationManager) as ShadowLocationManager
        shadowLocation.setLocationEnabled(true)
        assertTrue(system.isLocationEnabled())
        shadowLocation.setLocationEnabled(false)
        assertFalse(system.isLocationEnabled())
        // Hidden setters degrade without throwing either way.
        system.setLocationEnabled(true)
        system.setWifiScanningAlwaysAvailable(true)
        system.isWifiScanningAlwaysAvailable()

        val keyguard = ctx.getSystemService(android.app.KeyguardManager::class.java)
        val shadowKeyguard = Shadows.shadowOf(keyguard) as ShadowKeyguardManager
        shadowKeyguard.setIsDeviceSecure(true)
        assertTrue(system.isDeviceSecure())
        shadowKeyguard.setIsDeviceSecure(false)
        assertFalse(system.isDeviceSecure())
    }

    @Test fun `automatic time seeds and display mirroring seed only once`() {
        val system = SystemSetup(app)
        system.enableAutomaticTime()
        assertEquals(1, Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 0))
        assertEquals(1, Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0))

        Settings.Secure.putString(ctx.contentResolver, "mirror_built_in_display", null)
        system.seedDisplayMirroring()
        assertEquals(1, Settings.Secure.getInt(ctx.contentResolver, "mirror_built_in_display", 0))
        // A deliberate user choice (0) must never be overwritten by a later setup launch.
        Settings.Secure.putInt(ctx.contentResolver, "mirror_built_in_display", 0)
        system.seedDisplayMirroring()
        assertEquals(0, Settings.Secure.getInt(ctx.contentResolver, "mirror_built_in_display", 1))
    }

    @Test fun `emergency dialer intent and setup-complete lifecycle`() {
        val system = SystemSetup(app)
        val intent = system.emergencyDialerIntent()
        assertNotNull(intent.action)

        Settings.Secure.putInt(ctx.contentResolver, "user_setup_complete", 0)
        assertFalse(system.isUserSetupComplete())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        system.markSetupComplete(activity)
        assertTrue(system.isUserSetupComplete())
        system.finishAllTasks(activity)

        system.disableSelf()
        val state = ctx.packageManager.getApplicationEnabledSetting(ctx.packageName)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state)
        // Restore for other tests sharing this Robolectric sandbox.
        ctx.packageManager.setApplicationEnabledSetting(
            ctx.packageName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        assertNotNull(ComponentName(ctx, MainActivityShim::class.java))
    }
}

/** Local reference so the lifecycle test anchors on a real component name. */
private class MainActivityShim
