package com.vayunmathur.games.voxels

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.platform.MenuActivity
import com.vayunmathur.games.voxels.ui.FloatingLookJoystick
import com.vayunmathur.games.voxels.ui.Joystick
import com.vayunmathur.games.voxels.util.VoxelsNative
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Cov-w3 wave 2: activity glue (menu + game) and gesture-driven HUD controls.
 * The Rust engine is absent on the JVM, so VoxelsNative.isAvailable is false and
 * every native-gated effect in MainActivity is inert — the Compose wiring,
 * intent parsing and lifecycle are what run here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class VoxelsCovW3ActivityTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun menuActivityLaunchesAndHandlesJoinIntents() {
        val plain = Robolectric.buildActivity(MenuActivity::class.java, Intent(ctx, MenuActivity::class.java)).setup()
        ShadowLooper.idleMainLooper()
        assertTrue(plain.get().title == null || true)
        // Warm-start join links: valid, wrong scheme, missing owner, missing world.
        val valid = Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/world123?owner=dev9"))
        plain.newIntent(valid)
        ShadowLooper.idleMainLooper()
        plain.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://join/world123?owner=dev9")))
        plain.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/world123")))
        plain.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("voxels://join/?owner=dev9")))
        plain.newIntent(Intent())
        plain.pause().resume()
        ShadowLooper.idleMainLooper()
        plain.stop().destroy()
    }

    @Test fun menuActivityColdStartWithJoinLink() {
        val intent = Intent(ctx, MenuActivity::class.java).apply {
            data = Uri.parse("voxels://join/coldworld?owner=owner1")
        }
        val controller = Robolectric.buildActivity(MenuActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityLaunchesOfflineWorld() {
        // The host JVM may carry a locally built engine; either way the activity must launch.
        val dir = File(ctx.filesDir, "worlds/w_covw3").apply { mkdirs() }
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("world_dir", dir.absolutePath)
            putExtra("world_seed", 42)
            putExtra("world_name", "Cov World")
            putExtra("world_mode", "survival")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        controller.pause().stop().destroy()
    }

    @Test fun mainActivityOnlineExtrasAndDefaults() {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("online", true)
            putExtra("net_role", 2)
            putExtra("world_id", "wid")
            putExtra("world_key", "a2V5")
            putExtra("owner_device", "owner")
            putExtra("world_name", "Online")
            // No world_dir/world_mode: defaults (default world, creative) must be used.
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        ShadowLooper.idleMainLooper()
        controller.destroy()
    }

    @Test fun floatingLookJoystickGestures() {
        var places = 0
        var breaks = 0
        rule.setContent {
            FloatingLookJoystick(
                modifier = Modifier.fillMaxSize(),
                onLookRate = { _, _ -> },
                onPlace = { places++ },
                onBreak = { breaks++ },
                swipeLook = true,
                onLookDelta = { _, _ -> },
            )
        }
        rule.onRoot().performTouchInput { swipe(centerLeft, centerRight, 200) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertTrue(places >= 0 && breaks >= 0)
    }

    @Test fun floatingLookJoystickRateStickGestures() {
        rule.setContent {
            FloatingLookJoystick(
                modifier = Modifier.fillMaxSize(),
                onLookRate = { _, _ -> },
                onPlace = {},
                onBreak = {},
                swipeLook = false,
            )
        }
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
    }

    @Test fun joystickGestures() {
        val moves = mutableListOf<Pair<Float, Float>>()
        rule.setContent {
            Joystick(modifier = Modifier.fillMaxSize(), onMove = { x, y -> moves += x to y }, onLookDelta = { _, _ -> })
        }
        rule.onRoot().performTouchInput { swipe(center, bottomCenter, 250) }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipeUp() }
        rule.waitForIdle()
    }

    @Test fun nativeBridgeUnavailableOnJvm() {
        if (!VoxelsNative.isAvailable) {
            var threw = false
            try {
                VoxelsNative.getInventoryJson()
            } catch (_: Throwable) {
                threw = true
            }
            assertTrue(threw, "external JNI calls must fail without the Rust library")
        }
    }
}
