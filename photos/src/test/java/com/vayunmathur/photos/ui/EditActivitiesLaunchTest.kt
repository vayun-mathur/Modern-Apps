package com.vayunmathur.photos.ui

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.util.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The standalone editor activities launched the way the system launches
 * them (photo_id extra / ACTION_EDIT uri), over the in-memory library.
 */
@RunWith(AndroidJUnit4::class)
class EditActivitiesLaunchTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, video: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = 1L, width = 100, height = 100,
        dateModified = 1L, exifSet = true, lat = null, long = null,
        videoData = if (video) VideoData(5_000L) else null, panoData = null,
    )

    @Test fun editActivityOpensSeededPhoto() = runBlocking {
        installed.photos.upsertAll(listOf(photo("editme.jpg")))
        val id = installed.photos.getAll().first().id
        val intent = Intent(app, EditActivity::class.java).putExtra("photo_id", id)
        val activity = Robolectric.buildActivity(EditActivity::class.java, intent).setup().get()
        assertNotNull(activity)
        activity.finish()
        Unit
    }

    @Test fun editActivityResolvesEditActionUri() = runBlocking {
        installed.photos.upsertAll(listOf(photo("raw.jpg")))
        val seeded = installed.photos.getAll().first()
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(android.net.Uri.parse(seeded.uri), "image/jpeg")
        }
        val activity = Robolectric.buildActivity(EditActivity::class.java, intent).setup().get()
        assertNotNull(activity)
        activity.finish()
        // An unknown ACTION_EDIT uri becomes the stand-in photo 0.
        val unknown = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(android.net.Uri.parse("content://media/unknown.jpg"), "image/jpeg")
        }
        val second = Robolectric.buildActivity(EditActivity::class.java, unknown).setup().get()
        assertNotNull(second)
        second.finish()
        Unit
    }

    @Test fun videoEditActivityOpensSeededVideo() = runBlocking {
        installed.photos.upsertAll(listOf(photo("movie.mp4", video = true)))
        val id = installed.photos.getAll().first().id
        val intent = Intent(app, VideoEditActivity::class.java).putExtra("photo_id", id)
        val activity = Robolectric.buildActivity(VideoEditActivity::class.java, intent).setup().get()
        assertNotNull(activity)
        activity.finish()
        Unit
    }

    @Test fun videoEditActivityResolvesEditActionUri() = runBlocking {
        installed.photos.upsertAll(listOf(photo("shared.mp4", video = true)))
        val seeded = installed.photos.getAll().first()
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(android.net.Uri.parse(seeded.uri), "video/mp4")
        }
        val activity = Robolectric.buildActivity(VideoEditActivity::class.java, intent).setup().get()
        assertNotNull(activity)
        activity.finish()
        Unit
    }
}
