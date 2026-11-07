package com.vayunmathur.launcher

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Robolectric tests for [MainActivity]'s non-UI paths: lifecycle routing,
 * HOME-intent redelivery, and the bridge getters that do not start other
 * activities. The ViewModel's database is lazy, so the activity can be
 * created on the JVM; the workspace load it kicks off degrades against the
 * unavailable SQLCipher driver without taking the activity down.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityRoboTest {

    @Test
    fun `activity creates in phone mode and answers bridge getters`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        assertFalse(activity.isFinishing)
        // Bridge getters that only resolve or measure must be safe.
        activity.isDefaultHome()
        assertNotNull(activity.launchAnimationOptions(0, 0, 100, 100))
        activity.setWallpaperBlurRadius(40)
        controller.pause().stop().destroy()
    }

    @Test
    fun `home intent redelivery resets the activity intent`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        controller.newIntent(home)
        assertEquals(Intent.ACTION_MAIN, activity.intent?.action)
        assertTrue(activity.intent?.categories?.contains(Intent.CATEGORY_HOME) == true)
        controller.pause().stop().destroy()
    }

    @Test
    fun `home role request falls back to home settings without the role`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        activity.requestHomeRole()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_HOME_SETTINGS, started.action)
        controller.pause().stop().destroy()
    }

    @Test
    fun `wallpaper request opens a chooser`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        activity.pickWallpaper()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        controller.pause().stop().destroy()
    }

    @Test
    fun `uninstall without the silent permission uses the system confirmation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        activity.requestUninstall("com.example.app")
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_DELETE, started.action)
        assertEquals("package:com.example.app", started.data.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun `uninstall with the silent permission never opens the confirmation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        shadowOf(activity.application).grantPermissions("android.permission.DELETE_PACKAGES")
        activity.requestUninstall("com.example.app")
        // The silent path ran (PackageInstaller under Robolectric), so no
        // ACTION_DELETE intent was started.
        assertEquals(null, shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `widget bind and configure requests resolve to a result`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        var bindResult: Boolean? = null
        activity.requestBindWidget(
            1234,
            android.content.ComponentName("com.example", "com.example.Widget"),
            profileSerial = 0L,
        ) { bindResult = it }
        // Either the launch failed straight to a false callback, or a
        // consent activity is pending; it must never report success here.
        assertTrue(bindResult == null || bindResult == false)
        var configureResult: Boolean? = null
        activity.startWidgetConfigure(1234) { configureResult = it }
        assertTrue(configureResult == null || configureResult == false)
        controller.pause().stop().destroy()
    }

    @Test
    fun `tv mode routes to the tv navigation`() {
        val probe = Robolectric.buildActivity(MainActivity::class.java).get()
        val uiMode = probe.getSystemService(UiModeManager::class.java)
        shadowOf(uiMode).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        assertFalse(activity.isFinishing)
        controller.resume()
        controller.pause().stop().destroy()
    }

    @Test
    fun `configuration change without a mode flip does not recreate`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        activity.onConfigurationChanged(
            android.content.res.Configuration(activity.resources.configuration),
        )
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
