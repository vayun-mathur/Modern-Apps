package com.vayunmathur.photos

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [MainActivity] launched for real: the gallery root, an ACTION_VIEW
 * launch (which makes the viewed photo the stack root), and the
 * pushPhoto/relock helpers the navigation graph is built from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityRealLaunchTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() {
        workInfosInstall()
        TestPhotosRepository.install(app)
    }

    private fun workInfosInstall() {
        com.vayunmathur.photos.util.workInfosOverride = { _, _ -> kotlinx.coroutines.flow.flowOf(emptyList()) }
    }

    @Test fun plainLaunchReachesGallery() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java))
        val activity = controller.create().start().resume().get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }

    @Test fun actionViewVideoAndMalformedUris() {
        for (uri in listOf("content://media/external/video/media/7", "content://media/7", "file:///sdcard/x.jpg")) {
            val intent = Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(uri)
            }
            val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
            val activity = controller.create().start().resume().get()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(activity)
            controller.pause().stop().destroy()
        }
    }

    @Test fun warmStartNewIntentAndRecreate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java))
        val activity = controller.create().start().resume().get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Warm-start VIEW intent delivered to the running activity.
        val warm = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/external/images/media/9"))
        controller.newIntent(warm)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Configuration-change recreation restores the navigation stack.
        controller.recreate()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test fun actionViewLaunchRootsAtViewer() = runBlocking {
        TestPhotosRepository.install(app).photos.upsertAll(listOf(
            Photo(name = "a.jpg", uri = "content://media/external/images/media/42", date = 100L,
                width = 10, height = 10, dateModified = 100L, exifSet = true, lat = null, long = null,
                videoData = null, panoData = null, id = 42L),
        ))
        val intent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("content://media/external/images/media/42")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        val activity = controller.create().start().resume().get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Intent.ACTION_VIEW, activity.intent.action)
        controller.pause().stop().destroy()
        Unit
    }

    @Test fun pushPhotoReplacesTopViewerInsteadOfStacking() {
        val stack = com.vayunmathur.library.util.NavBackStack<Route>(arrayOf(Route.Gallery))
        stack.pushPhoto(Route.PhotoPage(1L, null))
        assertEquals(2, stack.backStack.size)
        stack.pushPhoto(Route.PhotoPage(2L, null))
        // Re-tapping a photo replaces the viewer rather than stacking it.
        assertEquals(2, stack.backStack.size)
        assertEquals(2L, (stack.last() as Route.PhotoPage).id)
        stack.pushPhoto(Route.PhotoPage(3L, null))
        assertEquals(2, stack.backStack.size)
        assertTrue(stack.backStack.first() == Route.Gallery)
    }
}
