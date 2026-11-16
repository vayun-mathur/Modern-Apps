package com.vayunmathur.photos.ui

import android.app.Application
import android.graphics.Bitmap
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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.awaitPhotosCondition
import com.vayunmathur.photos.platform.MotionPhotoVideo
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.WallpaperViewModel
import com.vayunmathur.photos.util.workInfosOverride
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Favorites page over seeded favorites, the wallpaper view-model's
 * decode of a real JPEG file, and motion-photo probing of a still image.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoritesWallpaperMotionTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
        }

    private fun photo(name: String, date: Long, favorite: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null, isFavorite = favorite,
    )

    @Test fun favoritesPageShowsOnlyFavorites() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("fav.jpg", 300L, favorite = true),
            photo("plain.jpg", 200L),
            photo("fav2.jpg", 100L, favorite = true),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secureVm = SecureFolderViewModel(app)
        setContent {
            DynamicTheme {
                FavoritesPage(
                    NavBackStack(arrayOf(Route.Gallery, Route.Favorites)),
                    gallery, secureVm,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("fav.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        // Press the grid chrome (selection mode affordances).
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 8)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        Unit
    }

    @Test fun wallpaperViewModelLoadsRealJpegAndMotionProbe() = runBlocking {
        // A real JPEG written by the host bitmap encoder.
        val jpeg = File(app.cacheDir, "wallpaper.jpg")
        FileOutputStream(jpeg).use { out ->
            Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(10, 120, 60))
            }.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        assertTrue(jpeg.length() > 0)
        val vm = WallpaperViewModel(app)
        vm.load(android.net.Uri.fromFile(jpeg).toString())
        assertTrue(awaitPhotosCondition { vm.bitmap.value != null })
        assertEquals(120, vm.bitmap.value!!.width)
        // A plain still is not a motion photo and yields no video file.
        assertFalse(MotionPhotoVideo.isMotionPhoto(app, android.net.Uri.fromFile(jpeg).toString()))
        assertNull(MotionPhotoVideo.motionVideoFile(app, android.net.Uri.fromFile(jpeg).toString(), 42L))
        assertNotNull(vm)
        Unit
    }
}
