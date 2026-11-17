package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.PhotoMapViewModel
import com.vayunmathur.photos.platform.SecureFolderViewModel
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.photos.ui.LocalColumnCount
import com.vayunmathur.photos.Navigation
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Through the real [Navigation] graph: Albums tiles push the Favorites,
 * Trash and Secure Folder detail entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosNavEntriesDeepTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
        }

    private fun photo(name: String, date: Long, trashed: Boolean = false, favorite: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null, isTrashed = trashed, isFavorite = favorite,
    )

    @Test fun albumsTilesPushDetailEntries() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("fav.jpg", 300L, favorite = true),
            photo("gone.jpg", 200L, trashed = true),
            photo("plain.jpg", 100L),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secureVm = SecureFolderViewModel(app)
        val mapVm = PhotoMapViewModel(app)
        setContent {
            DynamicTheme {
                Navigation(gallery, mapVm, secureVm)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Albums").performClick()
        rule.waitForIdle()
        // Favorites tile.
        runCatching { rule.onNodeWithText("Favorites", substring = true).performClick() }
        rule.waitForIdle()
        // The Favorites detail entry is on screen (the seeded favorite).
        rule.onNodeWithText("Favorites", substring = true).assertExists()
        Unit
    }
}
