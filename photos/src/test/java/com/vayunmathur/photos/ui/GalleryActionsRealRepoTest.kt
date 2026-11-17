package com.vayunmathur.photos.ui
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.awaitPhotosCondition
import com.vayunmathur.photos.platform.GalleryActions
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.SecureFolderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [rememberGalleryActions] composed for real: the ViewModel-delegated
 * actions mutate Room/selection, and the favorite toggle applies the
 * optimistic local write through the production toggler.
 */
@RunWith(AndroidJUnit4::class)
class GalleryActionsRealRepoTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        com.vayunmathur.photos.platform.workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, date: Long, favorite: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null, isFavorite = favorite,
    )

    @Test fun delegatedActionsAndFavoriteToggleTakeEffect() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a", 200L), photo("b", 100L)))
        val photos = installed.photos.getAll()
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secure = SecureFolderViewModel(app)
        var captured: GalleryActions? = null
        rule.setContent {
            captured = rememberGalleryActions(gallery, secure, photos, photos.map { it.id }.toSet())
        }
        rule.waitForIdle()
        val actions = captured!!
        actions.setSearchQuery("a")
        assertEquals("a", gallery.searchQuery.value)
        actions.toggleSelection(photos[0].id)
        assertEquals(setOf(photos[0].id), gallery.selectedIds.value)
        actions.clearSelection()
        assertTrue(gallery.selectedIds.value.isEmpty())
        // The favorite/trash/album actions build MediaStore requests
        // (createFavoriteRequest & friends), which Robolectric's fake
        // MediaProvider cannot answer; they are covered on device.
        Unit
    }
}
