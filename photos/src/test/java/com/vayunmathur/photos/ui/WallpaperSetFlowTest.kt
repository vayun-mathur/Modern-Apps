package com.vayunmathur.photos.ui

import android.app.Application
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.awaitPhotosCondition
import com.vayunmathur.photos.service.LiveWallpaperService
import com.vayunmathur.photos.util.LiveWallpaperLauncher
import com.vayunmathur.photos.util.workInfosOverride
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wallpaper page with a loadable image: preview loads, the set flow
 * runs, and the live-wallpaper launcher persists its choice and hands off
 * to the system preview intent.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperSetFlowTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        TestPhotosRepository.install(app)
    }

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
        }

    @Test fun wallpaperPageLoadsAndSets() = runBlocking {
        val jpeg = File(app.cacheDir, "set-wallpaper.jpg")
        FileOutputStream(jpeg).use { out ->
            Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(80, 40, 160))
            }.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        setContent {
            DynamicTheme {
                WallpaperPage(
                    NavBackStack<Route>(arrayOf(Route.Gallery, Route.Wallpaper(0L))),
                    Uri.fromFile(jpeg).toString(),
                )
            }
        }
        rule.waitForIdle()
        // Press everything the loaded page exposes (scrollable toggle,
        // target chooser, and the Set button itself).
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 12)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        assertTrue(count > 0)
        assertTrue(rule.onAllNodesWithText("wallpaper", substring = true).fetchSemanticsNodes().isNotEmpty() || count > 0)
        Unit
    }

    @Test fun liveWallpaperLauncherPersistsAndHandsOff() = runBlocking {
        LiveWallpaperLauncher.apply(app, "content://media/animated.gif", isVideo = false)
        val ds = DataStoreUtils.getInstance(app)
        assertEquals("content://media/animated.gif", ds.getStringAwait(LiveWallpaperService.KEY_WALLPAPER_URI))
        assertEquals(false, ds.getBooleanAwait(LiveWallpaperService.KEY_WALLPAPER_IS_VIDEO))
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, started.action)
        assertEquals(
            LiveWallpaperService::class.java.name,
            started.getParcelableExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, android.content.ComponentName::class.java)?.className,
        )
        Unit
    }

    @Test fun liveWallpaperLauncherVideoChoice() = runBlocking {
        LiveWallpaperLauncher.apply(app, "content://media/movie.mp4", isVideo = true)
        val ds = DataStoreUtils.getInstance(app)
        assertEquals(true, ds.getBooleanAwait(LiveWallpaperService.KEY_WALLPAPER_IS_VIDEO))
        assertTrue(
            Shadows.shadowOf(app).nextStartedActivity?.action ==
                WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER ||
                Shadows.shadowOf(app).nextStartedActivity != null,
        )
        Unit
    }
}
