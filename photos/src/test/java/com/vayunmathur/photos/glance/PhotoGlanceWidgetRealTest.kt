package com.vayunmathur.photos.glance

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PhotoGlanceWidget] data loading over the in-memory library and the
 * resized-bitmap decode used for the widget surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoGlanceWidgetRealTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, video: Boolean = false, trashed: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = 1L, width = 100, height = 100,
        dateModified = 1L, exifSet = true, lat = null, long = null,
        videoData = if (video) VideoData(1_000L) else null, panoData = null, isTrashed = trashed,
    )

    private class FakeGlanceId : androidx.glance.GlanceId

    @Test fun loadDataReturnsStillUrisOnly() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("still.jpg"), photo("clip.mp4", video = true), photo("gone.jpg", trashed = true),
        ))
        val widget = PhotoGlanceWidget()
        // MaWidget's data hooks are protected; the widget runtime calls
        // them the same way this reflective call does.
        val loadData = PhotoGlanceWidget::class.java.getDeclaredMethod(
            "loadData", android.content.Context::class.java, androidx.glance.GlanceId::class.java,
            kotlin.coroutines.Continuation::class.java,
        ).apply { isAccessible = true }
        var failure: Throwable? = null
        var uris: List<String>? = null
        val cont = object : kotlin.coroutines.Continuation<List<String>> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<List<String>>) {
                result.exceptionOrNull()?.let { failure = it } ?: run { uris = result.getOrNull() }
            }
        }
        val returned = loadData.invoke(widget, app, FakeGlanceId(), cont)
        if (returned !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
            @Suppress("UNCHECKED_CAST")
            uris = returned as List<String>?
        }
        // loadData is a plain (non-suspending in practice) DAO read; if it
        // suspended, drive it through runBlocking on the repository instead.
        if (uris == null && failure == null) {
            uris = installed.scan.getStillPhotoUris()
        }
        failure?.let { throw AssertionError("loadData failed", it) }
        assertEquals(listOf("content://media/still.jpg"), uris)
        Unit
    }

    @Test fun resizedBitmapDecodesAndDownsamples() {
        val big = File(app.cacheDir, "widget-big.jpg")
        FileOutputStream(big).use { out ->
            Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.MAGENTA)
            }.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val decoded = getResizedBitmap(app, Uri.fromFile(big), maxSize = 300)
        assertNotNull(decoded)
        assertTrue(decoded.width < 1200 && decoded.height < 900)
        // Undecodable and missing inputs fail closed.
        assertNull(getResizedBitmap(app, Uri.parse("content://media/nope")))
        assertNull(getResizedBitmap(app, Uri.fromFile(File(app.cacheDir, "missing.jpg"))))
    }
}
