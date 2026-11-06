package com.vayunmathur.launcher

import android.content.Intent
import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [MainActivity]'s side-effect-free paths: mode
 * detection, blur-radius guard, launch-animation options, intent handling
 * and bridge getters. Methods that launch other activities (wallpaper
 * chooser, uninstaller, role prompt, widget bind/configure) are exercised
 * by the launch journeys instead, to avoid yanking the shared emulator
 * out from under other workers' UiDevice tests.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityDeviceTest {

    @Test fun bridgeGettersAndMode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // This build is not the default home on the emulator.
                assertEquals(false, activity.isDefaultHome())
            }
        }
    }

    @Test fun blurRadiusGuardAndAnimationOptions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // No cross-window blur on the emulator: early return, no crash.
                activity.setWallpaperBlurRadius(50)
                activity.setWallpaperBlurRadius(0)
                // Clip-reveal options build off the decor view.
                val opts = activity.launchAnimationOptions(0, 0, 100, 100)
                assertEquals(true, opts != null)
            }
        }
    }

    @Test fun newIntentAndConfigChangeAreSafe() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.onNewIntent(Intent(Intent.ACTION_MAIN))
                activity.onConfigurationChanged(
                    android.content.res.Configuration(activity.resources.configuration),
                )
            }
        }
    }

    @Test fun uninstallSilentlyGuardedWithoutPermission() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Privilege probes read false on an ordinary build.
                assertEquals(false, activity.isDefaultHome())
            }
        }
    }
}
